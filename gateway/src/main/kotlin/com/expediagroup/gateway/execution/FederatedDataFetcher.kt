package com.expediagroup.gateway.execution

import com.expediagroup.graphql.server.types.GraphQLRequest
import com.expediagroup.graphql.server.types.GraphQLResponse
import graphql.language.Field
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.SelectionSet
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.future.future
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

/*
{
  "query": ...,
  "variables": {
    "_representations": [
      {
        "__typename": "Product",
        "upc": "B00005N5PF"
      },
      ...
    ]
  }
}

query ($_representations: [_Any!]!) {
  _entities(representations: $_representations) {
    ... on Product {
      reviews {
        body
      }
    }
  }
}
*/

class FederatedDataFetcher(
    private val __typeName: String,
    private val serviceToFieldsMapping: Map<String, Set<String>>,
    private val keySets: List<List<String>>,
    private val federatedFieldNames: Set<String>
) : DataFetcher<Any?> {

    private val clients = serviceToFieldsMapping.mapValues { (serviceUrl, _) -> WebClient.builder()
        .baseUrl(serviceUrl)
        .build()
    }

    private val fieldToServiceMapping = serviceToFieldsMapping.flatMap { (serviceUrl, fields) ->
        fields.map { fieldName -> fieldName to serviceUrl }
    }.toMap()

    @OptIn(DelicateCoroutinesApi::class)
    override fun get(environment: DataFetchingEnvironment): Any = GlobalScope.future {
        val parent = environment.getSource<Any>() as HashMap<String, Any>
        val result = parent[environment.field.name] as HashMap<String, Any?>

        val federatedKeys = HashMap<String, Any>()
        for (keys in keySets) {
            val keyCandidates = HashMap<String, Any>()
            var validKeys = true
            for (key in keys) {
                val keyValue = result[key]
                if (keyValue == null) {
                    validKeys = false
                } else {
                    keyCandidates[key] = keyValue
                }
            }

            if (validKeys) {
                federatedKeys.putAll(keyCandidates)
            }
        }
        if (federatedKeys.isEmpty()) {
            throw RuntimeException("unable to request federated entities - not all keys available")
        }


        val variables = mutableMapOf<String, MutableList<MutableMap<String, Any>>>()
        val representations = mutableListOf<MutableMap<String, Any>>()
        val representation = HashMap<String, Any>()
        representation["__typename"] = __typeName
        for ((key, value) in federatedKeys) {
            representation[key] = value
        }

        representations.add(representation)
        variables["_representations"] = representations

        // calculate federated field selection by service
        // first find the federated root selections
        val federatedFieldSelection = mutableListOf<Field>()
        calculateFederationRootFields(environment.field.selectionSet, federatedFieldNames, federatedFieldSelection)
        // split by service
        val fieldSelections = mutableMapOf<String, MutableSet<Field>>()
        for (field in federatedFieldSelection) {
            val targetService = fieldToServiceMapping[field.name]!!
            val selectedFields = fieldSelections.computeIfAbsent(targetService) { mutableSetOf() }
            selectedFields.add(field)
        }

        for ((serviceUrl, fieldSelection) in fieldSelections) {
            val federatedQuery = StringBuilder("query (${'$'}_representations: [_Any!]!) { _entities(representations: ${'$'}_representations) { ")
            federatedQuery.append(" ... on $__typeName ")

            buildFederatedQuery(fieldSelection, federatedQuery, HashMap())
            federatedQuery.append("} }")

            // TODO parallelize
            println("FEDERATED QUERY: \n$federatedQuery")
            val federationResult = clients[serviceUrl]!!.post()
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(GraphQLRequest(query = federatedQuery.toString(), variables = variables))
                .retrieve()
                .awaitBody<GraphQLResponse<Map<String, Any?>?>>()

            println("FEDERATED RESPONSE: $federationResult")

            // data = _entities = [ { foo bar } ]
            val entities = federationResult.data?.get("_entities") as List<HashMap<String, Any>>
            val entity = entities.first()

            for (federatedFieldName in federatedFieldNames) {
                entity[federatedFieldName]?.let {
                    result[federatedFieldName] = it
                }
            }
        }
        result
    }
}

fun buildFederatedQuery(federatedFieldSelection: Set<Field>, constructedQuery: StringBuilder, variables: MutableMap<String, Any?>) {
    constructedQuery.append("{ ")

    for (federatedField in federatedFieldSelection) {
        if (federatedField.arguments.isNotEmpty()) {
            constructArguments(federatedField.arguments, constructedQuery)
        }
        // TODO handle subfederation
        buildSubQuery(federatedField, constructedQuery, variables, emptySet())
    }
    constructedQuery.append(" }")
}

private fun calculateFederationRootFields(selectionSet: SelectionSet?, federatedFieldNames: Set<String>, federatedFields: MutableList<Field>) {
    if (selectionSet != null) {
        for (selection in selectionSet.selections) {
            when (selection) {
                is Field -> {
                    if (federatedFieldNames.contains(selection.name)) {
                        federatedFields.add(selection)
                    }
                }
                is SelectionSet -> calculateFederationRootFields(selection, federatedFieldNames, federatedFields)
                is FragmentSpread -> {
                    TODO("recursive")
                    // lookup named fragment from query
                    // use selection set
                }
                is InlineFragment -> {
                    calculateFederationRootFields(selection.selectionSet, federatedFieldNames, federatedFields)
                }
            }
        }
    }
}