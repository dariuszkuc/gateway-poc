package com.expediagroup.gateway.example

import com.expediagroup.graphql.generator.federation.directives.ExtendsDirective
import com.expediagroup.graphql.generator.federation.directives.ExternalDirective
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import com.expediagroup.graphql.generator.federation.execution.FederatedTypeResolver
import graphql.schema.DataFetchingEnvironment
import org.springframework.stereotype.Component
import java.util.UUID

@KeyDirective(fields = FieldSet("id"))
@ExtendsDirective
data class Bar(@ExternalDirective val id: Int) {
    fun federated(): String = "Hello From Federation!"

    fun federatedId(): String = UUID.randomUUID().toString()
}

@Component
class BarResolver : FederatedTypeResolver<Bar> {
    override val typeName: String = "Bar"

    override suspend fun resolve(
        environment: DataFetchingEnvironment,
        representations: List<Map<String, Any>>
    ): List<Bar?> = representations.map {
        val id = it["id"] as? Int

        if (id != null) {
            Bar(id)
        } else {
            null
        }
    }
}
