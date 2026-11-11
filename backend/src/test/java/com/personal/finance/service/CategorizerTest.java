package com.personal.finance.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.finance.entity.Direction;
import org.junit.jupiter.api.Test;

class CategorizerTest {

    @Test
    void commonPayeesGetUsefulCategories() {
        assertThat(Categorizer.categorize(Direction.DEBIT, "SWIGGY", "UPI", null)).isEqualTo("Food & drinks");
        assertThat(Categorizer.categorize(Direction.DEBIT, "Chennai Metro Rail", "UPI", null)).isEqualTo("Transport");
        assertThat(Categorizer.categorize(Direction.DEBIT, "Landlord Rao", "UPI", "House rent")).isEqualTo("Rent & housing");
        assertThat(Categorizer.categorize(Direction.DEBIT, "GROWW MUTUAL FUND", "UPI", "SIP")).isEqualTo("Investments");
        assertThat(Categorizer.categorize(Direction.DEBIT, "JIO PREPAID", "UPI", "Recharge")).isEqualTo("Bills & recharge");
    }

    @Test
    void cashAndSalaryAreRecognisedAndUnknownPayeesStayUnlabelled() {
        assertThat(Categorizer.categorize(Direction.DEBIT, "ATM WDL", "ATM", null)).isEqualTo("Cash withdrawal");
        assertThat(Categorizer.categorize(Direction.CREDIT, "ACME TECHNOLOGIES", "NEFT", "Salary")).isEqualTo("Salary");
        assertThat(Categorizer.categorize(Direction.DEBIT, "Ravi Kumar", "UPI", null)).isEqualTo("Other");
        assertThat(Categorizer.categorize(Direction.CREDIT, "Ravi Kumar", "UPI", null)).isEqualTo("Money received");
        assertThat(Categorizer.categorize(Direction.DEBIT, null, null, null)).isEqualTo("Other");
    }
}
