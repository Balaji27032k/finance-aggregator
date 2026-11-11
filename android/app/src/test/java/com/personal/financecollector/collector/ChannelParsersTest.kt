package com.personal.financecollector.collector

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Notification and e-mail parsing; bodies mirror real formats with made-up names, numbers and references. */
class ChannelParsersTest {

    private val now = 1_794_000_000_000L
    private val csbBoilerplate =
        "If this transaction was not done by you, SMS BLOCK [LAST 4 DIGITS of A/c Number] to 56161 or call 18002669090. " +
            "Warm regards, CSB Bank This is an auto generated e-mail. Please note CSB Bank will never ask for your CVV / OTP or PAN."

    private fun email(from: String, subject: String, body: String): ParsedTransaction {
        val t = EmailTransactionParser.parse(from, subject, body, now)
        assertNotNull("expected a parse for $subject", t)
        return t!!
    }

    @Test
    fun csbDebitAlertEmailKeepsPayeeAndTheUsersRemark() {
        val t = email(
            "noreply@csb.bank.in", "Account Debit Alert",
            "Dear Customer, Please note that an amount of INR 10.00 has been debited from your account XXXXXXXXX0000 towards " +
                "UPI/DR/631593452510/SAMPLE SHOP/FDRL/sample@upi/lunch on 11-NOV-2026. Your updated account balance is INR 545.20. $csbBoilerplate",
        )
        assertEquals("DEBIT", t.direction)
        assertEquals(BigDecimal("10.00"), t.amount)
        assertEquals("0000", t.accountLast4)
        assertEquals("SAMPLE SHOP", t.counterparty)
        assertEquals("631593452510", t.referenceNo)
        assertEquals("lunch", t.note)
        assertEquals(BigDecimal("545.20"), t.balanceAfter)
        assertEquals(LocalDate.of(2026, 11, 11), t.transactionDate)
    }

    @Test
    fun csbImpsCreditEmailReadsTheStructuredReference() {
        val t = email(
            "noreply@csb.bank.in", "Account Credit Alert",
            "Dear Customer, INR 50,000.00 credited to your A/c XXXXXXXXX0000 towards IMPS--630411647027-Sample Person-SBIN0000918-*******1111-NPCI " +
                "on 31-OCT-2026. Avl Bal: is INR 89,558.94. Best Regards, CSB Bank This is an auto generated e-mail.",
        )
        assertEquals("CREDIT", t.direction)
        assertEquals(BigDecimal("50000.00"), t.amount)
        assertEquals("Sample Person", t.counterparty)
        assertEquals("1111", t.counterpartyAccountLast4)
        assertEquals("630411647027", t.referenceNo)
        assertEquals("IMPS", t.paymentMode)
        assertEquals(BigDecimal("89558.94"), t.balanceAfter)
        assertNull(t.note)
    }

    @Test
    fun sbiNeftAndYonoTransferEmails() {
        val neft = email(
            "neftinfo.itps@alerts.sbi.bank.in", "NEFT Transaction",
            "Dear Customer, Thank you for banking with State Bank of India. Your account has been credited for NEFT received as per the details given below " +
                "Credited to Your A/c: XX0000 Amount: INR 1,35,353.00 UTR No.: CITIN26753479421 Date: 30/10/2026 Sent by: SAMPLE EMPLOYER LIMITED Sender Bank IFSC: CITI0100000",
        )
        assertEquals(BigDecimal("135353.00"), neft.amount)
        assertEquals("SAMPLE EMPLOYER LIMITED", neft.counterparty)
        assertEquals("CITIN26753479421", neft.referenceNo)
        assertEquals(LocalDate.of(2026, 10, 30), neft.transactionDate)

        val yono = email(
            "yonobysbi@alerts.sbi.bank.in", "Transaction success",
            "Dear Sample Thank you for using YONO SBI for Fund Transfer The transaction details are as follows: Description Details Transaction Status Successful " +
                "Amount Rs.50,000.00 Transaction Number 630411647027 Date of Transaction 31.10.26 Debit account x0000 Beneficiary Name SAMPLE " +
                "Beneficiary Account Number x1111 Reach Us at: In App",
        )
        assertEquals("DEBIT", yono.direction)
        assertEquals("1111", yono.counterpartyAccountLast4)
        assertEquals(LocalDate.of(2026, 10, 31), yono.transactionDate)
    }

    @Test
    fun otherEmailsAreNeverTransactions() {
        assertNull(EmailTransactionParser.parse("noreply@csb.bank.in", "OTP Alert", "Your One time password for New Deposit of INR80,000.00 from account ending XXXXXX7255418 is 156890.", now))
        assertNull(EmailTransactionParser.parse("noreply@csb.bank.in", "TD Open Alert", "A Term Deposit of INR 80,000.00 has been opened in your name.", now))
        assertNull(EmailTransactionParser.parse("noreply@csb.bank.in", "Account Statement Of 0545****5418 for the month of Oct", "Attached is your statement", now))
        assertNull(EmailTransactionParser.parse("dheeraj@croslime.info", "Account Debit Alert", "INR 10.00 debited from A/c XXXXXXXXX0000", now))
        assertNull(EmailTransactionParser.parse("yonobysbi@alerts.sbi.bank.in", "YONO Notification", "You have entered wrong MPIN twice", now))
    }

    @Test
    fun paymentAppNotificationsAreParsedByWording() {
        val received = TransactionParser.parseNotification("com.google.android.apps.nbu.paisa.user", "Sample Person paid you ₹500.00", now)!!
        assertEquals("CREDIT", received.direction)
        assertEquals(BigDecimal("500.00"), received.amount)
        assertEquals("Sample Person", received.counterparty)
        assertEquals("GPAY", received.bankCode)
        assertEquals("UPI", received.paymentMode)

        val paid = TransactionParser.parseNotification("com.phonepe.app", "You paid ₹250.00 to Sample Shop", now)!!
        assertEquals("DEBIT", paid.direction)
        assertEquals("Sample Shop", paid.counterparty)
        assertEquals("PHONEPE", paid.bankCode)

        assertEquals("Sample Person", TransactionParser.parseNotification("net.one97.paytm", "Received ₹100 from Sample Person", now)!!.counterparty)
        assertEquals(BigDecimal("30.00"), TransactionParser.parseNotification("net.one97.paytm", "Payment of ₹30 to Metro Bus", now)!!.amount)
    }

    @Test
    fun bankAppNotificationsReuseTheBankAlertEngine() {
        val t = TransactionParser.parseNotification("com.example.bankapp", "INR 10.00 debited from A/c XXXXXXXXX0000 on 11-NOV-2026. Avl Bal: INR 545.20", now)!!
        assertEquals("DEBIT", t.direction)
        assertEquals("0000", t.accountLast4)
        assertEquals(BigDecimal("545.20"), t.balanceAfter)
        assertEquals("BANKAPP", t.bankCode)
    }

    @Test
    fun promotionsRequestsAndVagueNotificationsAreNotTransactions() {
        listOf(
            "Get ₹50 cashback on your next payment",
            "Sample Shop requested ₹200 from you",
            "Payment successful ₹30",
            "Your reward of ₹10 is waiting",
            "Reminder: bill of ₹707 is due",
        ).forEach { assertNull(it, TransactionParser.parseNotification("com.google.android.apps.nbu.paisa.user", it, now)) }
    }
}
