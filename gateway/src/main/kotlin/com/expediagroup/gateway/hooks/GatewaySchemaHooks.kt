package com.example.schema

import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLUnionType

interface GatewaySchemaHooks {
    fun onEnumTypeMergeConflict(first: GraphQLEnumType, second: GraphQLEnumType): GraphQLEnumType

    fun onFieldDefinitionMergeConflict(first: GraphQLFieldDefinition, second: GraphQLFieldDefinition): GraphQLFieldDefinition

    fun onInputObjectMergeConflict(first: GraphQLInputObjectType, second: GraphQLInputObjectType): GraphQLInputObjectType

    fun onInterfaceTypeMergeConflict(first: GraphQLInterfaceType, second: GraphQLInterfaceType): GraphQLInterfaceType

    fun onObjectTypeMergeConflict(first: GraphQLObjectType, second: GraphQLObjectType): GraphQLObjectType

    fun onUnionTypeMergeConflict(first: GraphQLUnionType, second: GraphQLUnionType): GraphQLUnionType
}

class FederatedGatewaySchemaHooks : GatewaySchemaHooks {

    override fun onObjectTypeMergeConflict(
        first: GraphQLObjectType,
        second: GraphQLObjectType
    ): GraphQLObjectType {
        // TODO verify objects are federated -> currently we strip out those directives
//        if (first.getDirective("key") == null) {
//            throw RuntimeException("Object type merge conflict - $first is not federated object")
//        }
//        if (second.getDirective("key") == null) {
//            throw RuntimeException("Object type merge conflict - $second is not federated object")
//        }

        val mergedObjectBuilder = GraphQLObjectType.newObject(first)
        for (field in second.fieldDefinitions) {
            val newField: GraphQLFieldDefinition? = second.getFieldDefinition(field.name)
            // TODO validate external vs local fields
            val mergedFieldDefinition = if (newField != null) {
                onFieldDefinitionMergeConflict(field, newField)
            } else {
                field
            }
            mergedObjectBuilder.field(mergedFieldDefinition)
        }
        return mergedObjectBuilder.build()
    }

    override fun onFieldDefinitionMergeConflict(
        first: GraphQLFieldDefinition,
        second: GraphQLFieldDefinition
    ): GraphQLFieldDefinition {
        // TODO verify arguments
        // TODO verify return types
        return first
    }

    override fun onUnionTypeMergeConflict(first: GraphQLUnionType, second: GraphQLUnionType): GraphQLUnionType {
        // TODO verify same implementations
        return first
    }

    override fun onEnumTypeMergeConflict(first: GraphQLEnumType, second: GraphQLEnumType): GraphQLEnumType {
        if (first.values.size != second.values.size || !first.values.map { it.name }.containsAll(second.values.map { it.name })) {
            throw RuntimeException("Enum type conflict - enums define different values, first=$first, second=$second")
        }
        return first
    }

    override fun onInputObjectMergeConflict(
        first: GraphQLInputObjectType,
        second: GraphQLInputObjectType
    ): GraphQLInputObjectType {
        TODO("Not yet implemented")
    }

    override fun onInterfaceTypeMergeConflict(
        first: GraphQLInterfaceType,
        second: GraphQLInterfaceType
    ): GraphQLInterfaceType {
        TODO("Not yet implemented")
    }
}