package com.expediagroup.gateway

import com.expediagroup.graphql.server.extensions.toExecutionInput
import com.expediagroup.graphql.server.extensions.toGraphQLError
import com.expediagroup.graphql.server.extensions.toGraphQLKotlinType
import com.expediagroup.graphql.server.extensions.toGraphQLResponse
import com.expediagroup.graphql.server.types.GraphQLRequest
import com.expediagroup.graphql.server.types.GraphQLResponse
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.type.MapType
import com.fasterxml.jackson.databind.type.TypeFactory
import graphql.GraphQL
import kotlinx.coroutines.future.await
import kotlinx.coroutines.reactive.awaitFirst
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import org.springframework.web.reactive.function.server.coRouter
import org.springframework.web.reactive.function.server.json

@Configuration
class GatewayRouteConfig(
    private val graphQL: GraphQL,
    private val objectMapper: ObjectMapper
) {

    @Bean
    fun graphQLRoutes() = coRouter {
        val isEndpointRequest = POST("graphql") or GET("graphql")
        isEndpointRequest.invoke { serverRequest ->
            val graphQLRequest = parseRequest(serverRequest)
            if (graphQLRequest != null) {
                val executionInput = graphQLRequest.toExecutionInput()

                val graphQLResponse = try {
                    graphQL.executeAsync(executionInput).await().toGraphQLResponse()
                } catch (exception: Exception) {
                    val error = exception.toGraphQLError()
                    GraphQLResponse<Any?>(errors = listOf(error.toGraphQLKotlinType()))
                }
                ok().json().bodyValueAndAwait(graphQLResponse)
            } else {
                badRequest().buildAndAwait()
            }
        }
    }

    private val mapTypeReference: MapType = TypeFactory.defaultInstance().constructMapType(HashMap::class.java, String::class.java, Any::class.java)

    private suspend fun parseRequest(request: ServerRequest): GraphQLRequest? = when {
        request.queryParam("query").isPresent -> { getRequestFromGet(request) }
        request.method() == HttpMethod.POST -> { getRequestFromPost(request) }
        else -> null
    }

    private suspend fun getRequestFromGet(serverRequest: ServerRequest): GraphQLRequest {
        val query = serverRequest.queryParam("query").get()
        val operationName: String? = serverRequest.queryParam("operationName").orElseGet { null }
        val variables: String? = serverRequest.queryParam("variables").orElseGet { null }
        val graphQLVariables: Map<String, Any>? = variables?.let {
            objectMapper.readValue(it, mapTypeReference)
        }

        return GraphQLRequest(query = query, operationName = operationName, variables = graphQLVariables)
    }

    /**
     * We have have to suppress the warning due to a jackson issue
     * https://github.com/FasterXML/jackson-module-kotlin/issues/221
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun getRequestFromPost(serverRequest: ServerRequest): GraphQLRequest? {
        val contentType = serverRequest.headers().contentType().orElse(MediaType.APPLICATION_JSON)
        return when {
            contentType.includes(MediaType.APPLICATION_JSON) -> serverRequest.bodyToMono(GraphQLRequest::class.java).awaitFirst()
            contentType.includes(MediaType("application", "graphql")) -> GraphQLRequest(query = serverRequest.awaitBody())
            else -> null
        }
    }
}