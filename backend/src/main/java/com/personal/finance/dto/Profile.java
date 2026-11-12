package com.personal.finance.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** What the person tells the app about themselves so plans and advice fit them. Every field is optional. */
public record Profile(
        @Size(max = 40) String name,
        @Min(14) @Max(100) Integer age,
        @Pattern(regexp = "SALARIED|SELF_EMPLOYED|BUSINESS|STUDENT|RETIRED|OTHER") String occupation,
        @Min(0) @Max(1_000_000_000L) Long monthlyIncome,
        @Pattern(regexp = "STABLE|VARIABLE") String incomeStability,
        @Min(0) @Max(20) Integer dependents,
        @Pattern(regexp = "LOW|MEDIUM|HIGH") String riskComfort,
        @Pattern(regexp = "NONE|SOME|EXPERIENCED") String experience,
        @Pattern(regexp = "OLD|NEW|UNSURE") String taxRegime,
        @Min(0) @Max(10_000_000_000L) Long emergencyFund,
        @Min(0) @Max(10_000_000_000L) Long healthCover,
        @Min(0) @Max(100_000_000_000L) Long termCover,
        @Size(max = 10) List<@Valid @NotNull Goal> goals,
        @Size(max = 15) List<@Valid @NotNull Holding> investments,
        @Size(max = 8) List<@Valid @NotNull Loan> loans,
        @Valid Assumptions assumptions,
        @Size(max = 500) String notes) {

    public record Goal(
            @NotBlank @Size(max = 40) String name,
            @NotNull @Min(1) @Max(100_000_000_000L) Long amount,
            @Min(2026) @Max(2100) Integer year,
            @Pattern(regexp = "HIGH|MEDIUM|LOW") String priority) {
    }

    public record Holding(
            @NotBlank @Size(max = 30) String type,
            @NotNull @Min(0) @Max(100_000_000_000L) Long amount,
            @Min(0) @Max(1_000_000_000L) Long monthly) {
    }

    public record Loan(
            @NotBlank @Size(max = 30) String type,
            @NotNull @Min(0) @Max(100_000_000_000L) Long outstanding,
            @Min(0) @Max(1_000_000_000L) Long emi,
            @DecimalMin("0") @DecimalMax("40") Double rate) {
    }

    /** Annual percentages used in plans. Missing ones fall back to the defaults below. */
    public record Assumptions(
            @DecimalMin("0") @DecimalMax("30") Double inflation,
            @DecimalMin("0") @DecimalMax("30") Double equity,
            @DecimalMin("0") @DecimalMax("30") Double debt,
            @DecimalMin("0") @DecimalMax("30") Double fd,
            @DecimalMin("0") @DecimalMax("30") Double ppf,
            @DecimalMin("0") @DecimalMax("30") Double epf,
            @DecimalMin("0") @DecimalMax("30") Double savings) {

        public static final double INFLATION = 6;
        public static final double EQUITY = 12;
        public static final double DEBT = 7;
        public static final double FD = 7;
        public static final double PPF = 7.1;
        public static final double EPF = 8.25;
        public static final double SAVINGS = 3;
    }

    public static Profile empty() {
        return new Profile(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
