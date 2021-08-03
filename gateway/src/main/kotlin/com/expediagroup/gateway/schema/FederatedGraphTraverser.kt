package com.expediagroup.gateway.schema

import graphql.schema.FieldCoordinates
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeReference
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLUnionType

class FederatedGraphTraverser(
    private val typeCache: Map<String, GraphQLType>,
    private val polymorphicTypes: Map<String, Set<String>>,
    private val federatedTypesToFields: Map<String, Set<String>>
) {
    private val federatedPaths = mutableSetOf<String>()
    private val federatedTypesCoordinates = mutableMapOf<String, FieldCoordinates>()

    fun traverse(): FederatedGraphTraversalInfo {
        val query = typeCache["Query"] as? GraphQLObjectType ?: throw RuntimeException("query not found")
//        val mutation = typeCache["Mutation"] as? GraphQLObjectType ?: throw RuntimeException("mutation not found")

        createFederatedPaths(query)
//        createFederatedPaths(mutation, federatedPaths)

        println("PATHS: ${federatedPaths.joinToString("\n")}")
        // return federated type + coordinates for the federated types + federatedPaths
        return FederatedGraphTraversalInfo(federatedTypesCoordinates, federatedPaths)
    }

    private fun createFederatedPaths(
        type: GraphQLType,
        isFederated: Boolean = false,
        path: String = "",
        visited: Set<String> = emptySet(),
        coordinates: FieldCoordinates? = null
    ) {
        when (type) {
            is GraphQLList, is GraphQLNonNull -> {
                val unwrapped = GraphQLTypeUtil.unwrapOne(type)
                createFederatedPaths(unwrapped, isFederated, path, visited, coordinates)
            }
            is GraphQLObjectType -> {
                // skip cycles
                if (visited.contains(type.name)) {
                    return
                }

                // TODO if isFederated == true && federatedTypesToFields.contains(type.name) ==> sub-federation
                val federatedFieldSet = federatedTypesToFields[type.name] ?: emptySet()
                if (federatedTypesToFields.containsKey(type.name)) {
                    // current object is a federated type
                    federatedTypesCoordinates[type.name] = coordinates!!
                }

                for (field in type.fieldDefinitions) {
                    val isFieldFederated = federatedFieldSet.contains(field.name)
                    val updatedPath = if (path.isEmpty()) { field.name } else { path + ".${field.name}" }
                    // field coordinate = FieldCoordinates.coordinates(type.name, field.name)
                    createFederatedPaths(field.type, isFieldFederated, updatedPath, visited + type.name, FieldCoordinates.coordinates(type.name, field.name))
                }
            }
            is GraphQLUnionType, is GraphQLInterfaceType -> {
                // need explicit cast as when is not smart enough to deduct this
                val namedType = type as GraphQLNamedType
                if (visited.contains(namedType.name)) {
                    return
                }
                polymorphicTypes[namedType.name]?.forEach { implementationName ->
                    val implementation = typeCache[implementationName]!!
                    createFederatedPaths(implementation, isFederated, path, visited + namedType.name, coordinates)
                }
            }
            is GraphQLScalarType, is GraphQLEnumType -> {
                // leaf selection
                if (isFederated) {
                    federatedPaths.add(path)
                }
            }
            is GraphQLTypeReference -> {
                val referencedType = typeCache[type.name]!!
                createFederatedPaths(referencedType, isFederated, path, visited, coordinates)
            }
        }
    }
}

class FederatedGraphTraversalInfo(
    val federatedTypesCoordinates: Map<String, FieldCoordinates>,
    val federatedPaths: Set<String>
)