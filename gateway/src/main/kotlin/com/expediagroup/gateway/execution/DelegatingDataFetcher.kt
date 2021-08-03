package com.expediagroup.gateway.execution

import com.expediagroup.graphql.server.types.GraphQLRequest
import com.expediagroup.graphql.server.types.GraphQLResponse
import graphql.execution.DataFetcherResult
import graphql.language.Argument
import graphql.language.ArrayValue
import graphql.language.BooleanValue
import graphql.language.EnumValue
import graphql.language.Field
import graphql.language.FloatValue
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.IntValue
import graphql.language.NullValue
import graphql.language.ObjectValue
import graphql.language.SelectionSet
import graphql.language.StringValue
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.future.future
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

class DelegatingDataFetcher(
    targetUrl: String,
    private val federatedPaths: Set<String>
) : DataFetcher<Any?> {

    private val client = WebClient.builder()
        .baseUrl(targetUrl)
        .build()

    @OptIn(DelicateCoroutinesApi::class)
    override fun get(environment: DataFetchingEnvironment): Any {
        val updatedQuery = StringBuilder()
        val variables = HashMap<String, Any?>()
        updatedQuery.append("{ ")
        buildSubQuery(environment.field, updatedQuery, variables, federatedPaths)
        updatedQuery.append(" }")
        println("updated query: $updatedQuery")

        return GlobalScope.future {
            val response = client.post()
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(GraphQLRequest(query = updatedQuery.toString()))
                .retrieve()
                .awaitBody<GraphQLResponse<Map<String, Any?>?>>()
            DataFetcherResult.newResult<Any>()
                .data(response.data?.get(environment.field.name))
                // .errors(response.errors) // TODO handle error response
                .build()
        }
    }
}

fun buildSubQuery(rootSelection: Field, constructedQuery: StringBuilder, variables: MutableMap<String, Any?>, federatedPaths: Set<String> = emptySet()) {
    constructedQuery.append(rootSelection.name)
    if (rootSelection.arguments.isNotEmpty()) {
        constructArguments(rootSelection.arguments, constructedQuery)
    }
    buildSubQuery(rootSelection.selectionSet, constructedQuery, variables, rootSelection.name, federatedPaths)
}

fun buildSubQuery(selectionSet: SelectionSet?, constructedQuery: StringBuilder, variables: MutableMap<String, Any?>, path: String, federatedPaths: Set<String>) {
    if (selectionSet != null) {
        constructedQuery.append(" {")
        for (selection in selectionSet.selections) {
            when (selection) {
                is Field -> {
                    val updatedPath = "$path.${selection.name}"
                    if (!federatedPaths.contains(updatedPath)) {
                        constructedQuery.append(" ${selection.name}")
                        if (selection.arguments.isNotEmpty()) {
                            constructArguments(selection.arguments, constructedQuery)
                        }
                        buildSubQuery(selection.selectionSet, constructedQuery, variables, updatedPath, federatedPaths)
                    }
                }
                is SelectionSet -> buildSubQuery(selection, constructedQuery, variables, path, federatedPaths)
                is FragmentSpread -> TODO("recursive")
                is InlineFragment -> TODO("recursive")
            }
        }
        constructedQuery.append(" }")
    }
}

fun constructArguments(arguments: List<Argument>, constructedQuery: StringBuilder) {
    constructedQuery.append("(")
    for (argument in arguments) {
        val value: Any? = when(val rawArgumentValue = argument.value) {
            is ArrayValue -> TODO()
            is EnumValue -> TODO()
            is NullValue -> null
            is ObjectValue -> TODO("construct hash map from object fields?")
            is BooleanValue -> rawArgumentValue.isValue
            is FloatValue -> rawArgumentValue.value
            is IntValue -> rawArgumentValue.value
            is StringValue -> "\"${rawArgumentValue.value}\""
            else -> throw RuntimeException("unknown value")
        }
        constructedQuery.append("${argument.name}: $value")
        // TODO
//            variables[argument.name] = value
    }
    constructedQuery.append(")")
}