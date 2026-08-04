package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SwearGate contract: professional/formal tones get the word-list net,
 * every other tone passes untouched. Pure JVM — no Android, no mocks.
 */
class SwearGateTest {

    @Test fun casualPassesUntouched() {
        val r = SwearGate.apply("the deal looks fucking great", "casual")
        assertEquals("the deal looks fucking great", r.text)
        assertEquals(0, r.caught)
    }

    @Test fun professionalDropsModifierSwear() {
        val r = SwearGate.apply("The deal looks fucking great.", "professional")
        assertEquals("The deal looks great.", r.text)
        assertEquals(1, r.caught)
    }

    @Test fun formalIsGatedToo() {
        val r = SwearGate.apply("That report is bullshit.", "formal")
        assertEquals("That report is.", r.text)
        assertEquals(1, r.caught)
    }

    @Test fun leadingInterjectionTidiedAndRecapitalized() {
        val r = SwearGate.apply("Fuck, the deal looks great.", "professional")
        assertEquals("The deal looks great.", r.text)
        assertEquals(1, r.caught)
    }

    @Test fun multipleSwearsAllCaught() {
        val r = SwearGate.apply("Shit, this fucking printer is damn slow.", "professional")
        assertEquals(3, r.caught)
        assertEquals("This printer is slow.", r.text)
    }

    @Test fun wordBoundarySafety() {
        val r = SwearGate.apply("The class assassin passed Scunthorpe with a shiitake dish.", "professional")
        assertEquals(0, r.caught)
        assertEquals("The class assassin passed Scunthorpe with a shiitake dish.", r.text)
    }

    @Test fun russianSwearCaught() {
        val r = SwearGate.apply("Отчёт готов, сука, отправляю вечером.", "professional")
        assertEquals(1, r.caught)
        assertEquals("Отчёт готов, отправляю вечером.", r.text)
    }

    @Test fun allSwearInputFallsBackToOriginal() {
        val r = SwearGate.apply("Fuck.", "professional")
        assertEquals("Fuck.", r.text)
        assertEquals(0, r.caught)
    }

    @Test fun cleanTextUnchanged() {
        val r = SwearGate.apply("Please send the contract tonight.", "professional")
        assertEquals("Please send the contract tonight.", r.text)
        assertEquals(0, r.caught)
    }
}
