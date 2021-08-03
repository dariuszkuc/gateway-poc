package com.expediagroup.gateway.example

import com.expediagroup.graphql.server.operations.Query
import org.springframework.stereotype.Component

@Component
class GoodbyeWorldQuery : Query {

    fun goodbyeWorld(name: String? = null): String = if (name != null) {
        "Goodbye $name!"
    } else {
        "Goodbye World!"
    }
}