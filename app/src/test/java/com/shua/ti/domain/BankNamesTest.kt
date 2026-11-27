package com.shua.ti.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class BankNamesTest {

    @Test
    fun `free name stays unchanged`() {
        assertEquals("数学.json", BankNames.unique("数学.json", emptySet()))
    }

    @Test
    fun `duplicate gets suffix one`() {
        assertEquals("数学 (1).json", BankNames.unique("数学.json", setOf("数学.json")))
    }

    @Test
    fun `existing suffix increments`() {
        val existing = setOf("数学.json", "数学 (1).json")
        assertEquals("数学 (2).json", BankNames.unique("数学 (1).json", existing))
    }

    @Test
    fun `gap is filled by first free index`() {
        val existing = setOf("数学.json", "数学 (2).json")
        assertEquals("数学 (1).json", BankNames.unique("数学.json", existing))
    }

    @Test
    fun `name without extension still gets suffix`() {
        assertEquals("abc (1)", BankNames.unique("abc", setOf("abc")))
    }

    @Test
    fun `dot inside base name is not treated as extension`() {
        assertEquals("1.1 (1)", BankNames.unique("1.1", setOf("1.1")))
    }

    @Test
    fun `extension is kept when user omits it`() {
        assertEquals("高数.json", BankNames.withOriginalExtension("高数", "sample-bank.json"))
    }

    @Test
    fun `user provided extension is respected`() {
        assertEquals("高数.txt", BankNames.withOriginalExtension("高数.txt", "sample-bank.json"))
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals("", BankNames.withOriginalExtension("   ", "a.json"))
    }
}
