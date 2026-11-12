package com.personal.financecollector.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileJsonTest {

    private val profile = MoneyProfile(
        name = "Bala", age = 32, occupation = "SALARIED", monthlyIncome = 80000, incomeStability = "STABLE", dependents = 2,
        riskComfort = "MEDIUM", experience = "SOME", taxRegime = "NEW", emergencyFund = 150000, healthCover = 500000, termCover = null,
        goals = listOf(ProfileGoal("House", 1500000, 2032, "HIGH")),
        investments = listOf(ProfileHolding("PPF", 300000, 5000)),
        loans = listOf(ProfileLoan("Car loan", 400000, 12000, 9.5)),
        rates = ProfileRates(equity = 12.0, ppf = 7.1),
        notes = "Planning for a house",
    )

    @Test
    fun aProfileSurvivesTheTripToTheServerAndBack() {
        val back = ProfileJson.parse(ProfileJson.toJson(profile))
        assertEquals(profile, back)
    }

    @Test
    fun missingValuesStayMissingAndAnEmptyProfileIsReadable() {
        val back = ProfileJson.parse("""{"age":null,"goals":[],"assumptions":null}""")!!
        assertNull(back.age)
        assertNull(back.rates.equity)
        assertTrue(back.goals.isEmpty())
        assertEquals(0, back.completeness())
        assertNotNull(ProfileJson.parse(ProfileJson.toJson(MoneyProfile())))
        assertNull(ProfileJson.parse("not json"))
    }

    @Test
    fun completenessCountsWhatTheCoachNeeds() {
        assertEquals(100, profile.copy(termCover = 1).completeness())
        assertTrue(profile.completeness() in 90..100)
        assertEquals(9, MoneyProfile(age = 30).completeness())
    }

    @Test
    fun theRequestHoldsNullsAsJsonNullsAndTrimsLongText() {
        val json = org.json.JSONObject(ProfileJson.toJson(MoneyProfile(name = " ".repeat(3), notes = "x".repeat(900))))
        assertTrue(json.isNull("name"))
        assertEquals(500, json.getString("notes").length)
        assertTrue(json.isNull("age"))
        assertEquals(0, json.getJSONArray("goals").length())
    }
}
