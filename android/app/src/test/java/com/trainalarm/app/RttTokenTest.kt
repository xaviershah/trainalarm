package com.trainalarm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RttTokenTest {
    @Test fun missingOrEmptyValuesAreNoToken() {
        assertNull(RttToken.parse(null))
        assertNull(RttToken.parse(""))
        assertNull(RttToken.parse("   "))
    }

    @Test fun aRealValueIsTrimmed() = assertEquals("abc123", RttToken.parse("  abc123\n"))
}
