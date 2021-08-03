package com.expediagroup.gateway.example

import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import com.expediagroup.graphql.server.operations.Query
import org.springframework.stereotype.Component

@Component
class FooQuery : Query {

    fun foo(): Foo = Foo("abcd")
}

data class Foo(val id: String) {
    fun bar(): Bar = Bar(123)
}

@KeyDirective(fields = FieldSet("id"))
data class Bar(val id: Int) {
    fun baz(): Baz = Baz("foobar")
}

data class Baz(val name: String)