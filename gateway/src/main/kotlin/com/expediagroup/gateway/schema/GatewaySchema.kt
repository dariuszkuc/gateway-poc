package com.expediagroup.gateway.schema

import com.example.schema.GatewaySchemaHooks
import com.expediagroup.gateway.config.FederatedConfig
import com.expediagroup.gateway.execution.DelegatingDataFetcher
import com.expediagroup.gateway.execution.FederatedDataFetcher
import com.expediagroup.gateway.schema.scalars.CustomScalarCoercing
import graphql.TypeResolutionEnvironment
import graphql.introspection.Introspection
import graphql.kickstart.tools.GRAPHQL_SCALARS
import graphql.language.DirectiveDefinition
import graphql.language.EnumTypeDefinition
import graphql.language.FieldDefinition
import graphql.language.InputObjectTypeDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.ScalarTypeDefinition
import graphql.language.StringValue
import graphql.language.Type
import graphql.language.TypeName
import graphql.language.UnionTypeDefinition
import graphql.schema.FieldCoordinates
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLCodeRegistry
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLEnumValueDefinition
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeReference
import graphql.schema.GraphQLUnionType
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.SchemaPrinter
import graphql.schema.idl.TypeDefinitionRegistry
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

class GatewaySchema(
    private val hooks: GatewaySchemaHooks
) {
    private val logger: Logger = LoggerFactory.getLogger(GatewaySchema::class.java)
    private val federatedDirectives: Set<String> = setOf("extends", "external", "key", "provides", "requires")
    private val federatedScalars: Set<String> = setOf("_Any", "_FieldSet")

    // schema contents
    private val types = mutableMapOf<String, GraphQLType>()
    private val polymorphicTypes = mutableMapOf<String, MutableSet<String>>()
    private val directives = mutableMapOf<String, GraphQLDirective>()

    // information for data fetchers
    private val rootFieldsToTargetServiceUrl = mutableMapOf<FieldCoordinates, String>()
    private val federatedObjectToKeySets = mutableMapOf<String, MutableList<List<String>>>()
    // holds information about all federated types and their federated fields served by a given service
    //   federated type -> serviceURL -> federated fields
    private val federatedServiceInformation = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()

    private val codeRegistry = GraphQLCodeRegistry.newCodeRegistry()

    fun generate(config: FederatedConfig): GraphQLSchema {
        for ((serviceName, endpoint, schemaFile) in config.services) {
            parseSchema(endpoint, File(schemaFile))
        }

        // post process schema to populate code registry with appropriate data fetchers
        //
        // since types can be federated across multiple services we first calculate information about ALL federated
        // fields so we can exclude them from query plans
        val federatedFields = mutableMapOf<String, MutableSet<String>>()
        federatedServiceInformation.forEach { (type, serviceToFieldsMapping) ->
            val fieldSet = federatedFields.computeIfAbsent(type) { mutableSetOf() }
            serviceToFieldsMapping.values.forEach { fields ->
                fieldSet.addAll(fields)
            }
        }

        // traverse query/mutation/subscription trees and locate all federated entries
        val traversalInfo = FederatedGraphTraverser(types, polymorphicTypes, federatedFields).traverse()

        // TODO DEBUG
        println("FEDERATED PATHS: $traversalInfo")
        println("FEDERATED SERVICES: $federatedServiceInformation")
        println("FEDERATED KEYSETS: $federatedObjectToKeySets")

        // register root queries
        for ((coordinates, serviceUrl) in rootFieldsToTargetServiceUrl) {
            codeRegistry.dataFetcher(coordinates, DelegatingDataFetcher(serviceUrl, traversalInfo.federatedPaths))
        }
        // register federated queries
        for ((federatedType, coordinates) in traversalInfo.federatedTypesCoordinates) {
            val serviceToFieldsMapping = federatedServiceInformation[federatedType]
            codeRegistry.dataFetcher(coordinates, FederatedDataFetcher(federatedType, serviceToFieldsMapping!!, federatedObjectToKeySets[federatedType]!!, federatedFields[federatedType]!!))
        }

        val query = types["Query"] as? GraphQLObjectType ?: throw RuntimeException("query not found")
//        val mutation = types["Mutation"] as? GraphQLObjectType ?: throw RuntimeException("mutation not found")
        val additionalTypes = types.filter { it.key != "Query" && it.key != "Mutation" }.values.toSet()
        val schema = GraphQLSchema.newSchema()
            .query(query)
//            .mutation(mutation)
            .additionalTypes(additionalTypes)
            .additionalDirectives(directives.values.toSet())
            .codeRegistry(codeRegistry.build())
            .build()

        println(SchemaPrinter().print(schema))
        return schema
    }

    private fun parseSchema(serviceUrl: String, schemaFile: File) {
        // graphql-java does not allow merging of query types -> have to rebuild the schema
        val typeRegistry = SchemaParser().parse(schemaFile)

        typeRegistry.directiveDefinitions.forEach { (_, definition) ->
            mergeDirectives(definition)
        }
        typeRegistry.scalars().forEach { (_, definition) ->
            mergeScalars(definition)
        }
        typeRegistry.types().forEach { (_, type) ->
            when (type) {
                is ObjectTypeDefinition -> mergeObjectType(type)
                is InterfaceTypeDefinition -> mergeInterfaceType(type)
                is UnionTypeDefinition -> mergeUnionType(type)
                is EnumTypeDefinition -> mergeEnumType(type)
                is InputObjectTypeDefinition -> mergeInputObjectType(type)
                is ScalarTypeDefinition -> mergeScalars(type) // should have already been processed
            }
        }

        // capture root query/mutation fields
        populateRootFields(typeRegistry.getType("Query", ObjectTypeDefinition::class.java).orElse(null), serviceUrl)
        populateRootFields(typeRegistry.getType("Mutation", ObjectTypeDefinition::class.java).orElse(null), serviceUrl)

        // post process federation stuff
        processFederatedObjects(typeRegistry, serviceUrl)
    }

    private fun mergeDirectives(directiveDefinition: DirectiveDefinition) {
        if (federatedDirectives.contains(directiveDefinition.name)) {
            return
        }
        // TODO check if directive definitions are different
        directives.computeIfAbsent(directiveDefinition.name) {
            createDirective(directiveDefinition)
        }
    }

    private fun createDirective(definition: DirectiveDefinition): GraphQLDirective {
        val directiveBuilder = GraphQLDirective.newDirective()
            .name(definition.name)
            .description(definition.description?.content)
            .definition(definition)
            .validLocations(*definition.directiveLocations
                .map { location -> Introspection.DirectiveLocation.valueOf(location.name) }
                .toTypedArray()
            )

        definition.inputValueDefinitions.forEach { input ->
            val argument = GraphQLArgument.newArgument()
                .name(input.name)
                .value(input.defaultValue)
                .type(createInputTypeReference(input.type))
                .build()
            directiveBuilder.argument(argument)
        }

        return directiveBuilder.build()
    }

    private fun mergeScalars(scalarDefinition: ScalarTypeDefinition) {
        if (!GRAPHQL_SCALARS.containsKey(scalarDefinition.name) && !federatedScalars.contains(scalarDefinition.name)) { // skip built-in scalars
            types.computeIfAbsent(scalarDefinition.name) {
                createScalarType(scalarDefinition)
            }
        }
    }

    private fun createScalarType(definition: ScalarTypeDefinition): GraphQLScalarType = GraphQLScalarType.newScalar()
        .name(definition.name)
        .description(definition.description?.content)
        .definition(definition)
        .coercing(CustomScalarCoercing)
        .build()

    private fun mergeObjectType(definition: ObjectTypeDefinition) {
        if (definition.name == "_Service") {
            return
        }

        // graphql-java builders don't expose underlying fields so in order to resolve merge conflicts we need built type
        val newType = createObjectType(definition)
        val existingType = types[definition.name]
        if (existingType != null) {
            if (existingType !is GraphQLObjectType) {
                throw RuntimeException("Type conflict - two different types defined with same names, first=$existingType, second=$newType")
            }
            types[definition.name] = hooks.onObjectTypeMergeConflict(existingType, newType)
        } else {
            types[definition.name] = newType
        }
    }

    private fun createObjectType(typeDefinition: ObjectTypeDefinition) : GraphQLObjectType {
        val objectBuilder = GraphQLObjectType.newObject()
            .name(typeDefinition.name)
            .definition(typeDefinition)
            .description(typeDefinition.description?.content)

        typeDefinition.directives.filter { !federatedDirectives.contains(it.name) }.forEach { directive ->
            objectBuilder.withDirective(directives[directive.name])
        }
        typeDefinition.implements.filterIsInstance<TypeName>().forEach { graphQLInterface ->
            val implementations = polymorphicTypes.computeIfAbsent(graphQLInterface.name) { mutableSetOf() }
            implementations.add(typeDefinition.name)

            objectBuilder.withInterface(GraphQLTypeReference(graphQLInterface.name))
        }
        typeDefinition.fieldDefinitions.filter { it.name != "_entities" && it.name != "_service" }.forEach { fieldDefinition ->
            objectBuilder.field(createField(fieldDefinition))
        }
        return objectBuilder.build()
    }

    private fun mergeInterfaceType(definition: InterfaceTypeDefinition) {
        val newInterface = createInterfaceType(definition)
        val existingInterface = types[definition.name]
        if (existingInterface != null) {
            if (existingInterface !is GraphQLInterfaceType) {
                throw RuntimeException("Type conflict - two different types defined with same names, first=$existingInterface, second=$newInterface")
            }
            types[definition.name] = hooks.onInterfaceTypeMergeConflict(existingInterface, newInterface)
        } else {
            types[definition.name] = newInterface
        }
    }

    private fun createInterfaceType(definition: InterfaceTypeDefinition): GraphQLInterfaceType {
        val interfaceBuilder = GraphQLInterfaceType.newInterface()
            .name(definition.name)
            .definition(definition)
            .description(definition.description?.content)

        definition.directives.filter { !federatedDirectives.contains(it.name) }.forEach { directive ->
            interfaceBuilder.withDirective(directives[directive.name])
        }
        definition.fieldDefinitions.forEach { fieldDefinition ->
            interfaceBuilder.field(createField(fieldDefinition))
        }
        definition.implements.filterIsInstance<TypeName>().forEach { graphQLInterface ->
            interfaceBuilder.withInterface(GraphQLTypeReference(graphQLInterface.name))
        }

        return interfaceBuilder.build()
    }

    private fun createField(fieldDefinition: FieldDefinition): GraphQLFieldDefinition {
        val fieldDefinitionBuilder = GraphQLFieldDefinition.newFieldDefinition()
            .name(fieldDefinition.name)
            .definition(fieldDefinition)
            .description(fieldDefinition.description?.content)
            .type(createOutputTypeReference(fieldDefinition.type))

        fieldDefinition.directives.filter { !federatedDirectives.contains(it.name) }.forEach { directive ->
            fieldDefinitionBuilder.withDirective(directives[directive.name])
        }
        fieldDefinition.inputValueDefinitions.forEach { inputValueDefinition ->
            val argumentBuilder = GraphQLArgument.newArgument()
                .name(inputValueDefinition.name)
                .definition(inputValueDefinition)
                .description(inputValueDefinition.description?.content)
                .type(createInputTypeReference(inputValueDefinition.type))

            fieldDefinitionBuilder.argument(argumentBuilder.build())
        }
        return fieldDefinitionBuilder.build()
    }

    private fun mergeUnionType(definition: UnionTypeDefinition) {
        if (definition.name == "_Entity") {
            return
        }

        val newUnion = createUnionType(definition)
        val existingUnion = types[definition.name]
        if (existingUnion != null) {
            if (existingUnion !is GraphQLUnionType) {
                throw RuntimeException("Type conflict - two different types defined with same names, first=$existingUnion, second=$newUnion")
            }
            types[definition.name] = hooks.onUnionTypeMergeConflict(existingUnion, newUnion)
        } else {
            types[definition.name] = newUnion
        }
    }

    private fun createUnionType(unionDefinition: UnionTypeDefinition): GraphQLUnionType {
        val unionBuilder = GraphQLUnionType.newUnionType()
            .name(unionDefinition.name)
            .description(unionDefinition.description?.content)
            .definition(unionDefinition)

        unionDefinition.directives.filter { !federatedDirectives.contains(it.name) }.forEach { directive ->
            unionBuilder.withDirective(directives[directive.name])
        }
        val unionMembers = polymorphicTypes.computeIfAbsent(unionDefinition.name) { mutableSetOf() }
        unionDefinition.memberTypes.filterIsInstance<TypeName>().forEach { unionMember ->
            unionMembers.add(unionMember.name)
            unionBuilder.possibleType(GraphQLTypeReference(unionMember.name))
        }
        val unionType = unionBuilder.build()
        codeRegistry.typeResolver(unionType) { env: TypeResolutionEnvironment -> env.schema.getObjectType(env.getObject<Any>().javaClass.kotlin.simpleName) }
        return unionType
    }

    private fun mergeEnumType(definition: EnumTypeDefinition) {
        val newEnum = createEnum(definition)
        val existingEnum = types[definition.name]
        if (existingEnum != null) {
            if (existingEnum !is GraphQLEnumType) {
                throw RuntimeException("Type conflict - two different types defined with same names, first=$existingEnum, second=$newEnum")
            }
            types[definition.name] = hooks.onEnumTypeMergeConflict(existingEnum, newEnum)
        } else {
            types[definition.name] = newEnum
        }
    }

    private fun createEnum(definition: EnumTypeDefinition): GraphQLEnumType {
        val enumBuilder = GraphQLEnumType.newEnum()
            .name(definition.name)
            .definition(definition)
            .description(definition.description?.content)

        definition.directives.filter { !federatedDirectives.contains(it.name) }.forEach { directive ->
            enumBuilder.withDirective(directives[directive.name])
        }
        definition.enumValueDefinitions.forEach { enumValueDefinition ->
            val enumValueBuilder = GraphQLEnumValueDefinition.newEnumValueDefinition()
                .name(enumValueDefinition.name)
                .definition(enumValueDefinition)
                .description(enumValueDefinition.description?.content)

            enumValueDefinition.directives.filter { !federatedDirectives.contains(it.name) }.forEach { directive ->
                enumValueBuilder.withDirective(directives[directive.name])
            }
            enumBuilder.value(enumValueBuilder.build())
        }

        return enumBuilder.build()
    }

    private fun mergeInputObjectType(definition: InputObjectTypeDefinition) {
        val newInput = createInputObjectType(definition)
        val existingInput = types[definition.name]
        if (existingInput != null) {
            if (existingInput !is GraphQLInputObjectType) {
                throw RuntimeException("Type conflict - two different types defined with same names, first=$existingInput, second=$newInput")
            }
            types[definition.name] = hooks.onInputObjectMergeConflict(existingInput, newInput)
        } else {
            types[definition.name] = newInput
        }
    }

    private fun createInputObjectType(definition: InputObjectTypeDefinition): GraphQLInputObjectType {
        val inputObjectBuilder = GraphQLInputObjectType.newInputObject()
            .name(definition.name)
            .definition(definition)
            .description(definition.description?.content)

        definition.directives.filter { !federatedDirectives.contains(it.name) }.forEach { directive ->
            inputObjectBuilder.withDirective(directives[directive.name])
        }
        definition.inputValueDefinitions.forEach { inputValueDefinition ->
            val inputValue = GraphQLInputObjectField.newInputObjectField()
                .name(inputValueDefinition.name)
                .definition(inputValueDefinition)
                .description(inputValueDefinition.description?.content)
                .type(createInputTypeReference(inputValueDefinition.type))
                .build()
            inputObjectBuilder.field(inputValue)
        }

        return inputObjectBuilder.build()
    }

    private fun createInputTypeReference(type: Type<*>): GraphQLInputType {
        return when (type) {
            is NonNullType -> GraphQLNonNull(createInputTypeReference(type.type))
            is ListType -> GraphQLList(createInputTypeReference(type.type))
            is TypeName -> {
                GRAPHQL_SCALARS[type.name] ?: GraphQLTypeReference(type.name)
            }
            else -> throw RuntimeException("unsupported input type $type") // should never be the case
        }
    }

    private fun createOutputTypeReference(type: Type<*>): GraphQLOutputType {
        return when (type) {
            is NonNullType -> GraphQLNonNull(createOutputTypeReference(type.type))
            is ListType -> GraphQLList(createOutputTypeReference(type.type))
            is TypeName -> {
                GRAPHQL_SCALARS[type.name] ?: GraphQLTypeReference(type.name)
            }
            else -> throw RuntimeException("unsupported output type $type") // should never be the case
        }
    }

    private fun populateRootFields(rootDefinition: ObjectTypeDefinition?, serviceUrl: String) {
        rootDefinition?.also { rootObject ->
            rootObject.fieldDefinitions.forEach { rootField ->
                rootFieldsToTargetServiceUrl[FieldCoordinates.coordinates(rootObject.name, rootField.name)] = serviceUrl
            }
        }
    }

    private fun processFederatedObjects(typeRegistry: TypeDefinitionRegistry, serviceUrl: String) {
        val (federatedBaseObjects, federatedExtendedObjects) = typeRegistry.types().values
            .asSequence()
            .filterIsInstance<ObjectTypeDefinition>()
            .filter { type -> type.hasDirective("key") }
            .partition { type -> !type.hasDirective("extends") }

        for (baseObject in federatedBaseObjects) {
            val keys = mutableListOf<List<String>>()
            for (keyDirective in baseObject.getDirectives("key")) {
                val keySet = keyDirective.getArgument("fields").value as StringValue
                // TODO split keySet values
                keys.add(listOf(keySet.value))
            }
            federatedObjectToKeySets[baseObject.name] = keys
            logger.debug("Computed federated keys for ${baseObject.name}, keys=$keys")
        }

        for (extendedObject in federatedExtendedObjects) {
            val federatedTypeInfo = federatedServiceInformation.computeIfAbsent(extendedObject.name) { HashMap() }
            val federatedFieldsServedByService = federatedTypeInfo.computeIfAbsent(serviceUrl) { mutableSetOf() }
            extendedObject.fieldDefinitions
                .filter { field -> !field.hasDirective("external") }
                .forEach { field ->
                    federatedFieldsServedByService.add(field.name)
                }
        }
    }
}

