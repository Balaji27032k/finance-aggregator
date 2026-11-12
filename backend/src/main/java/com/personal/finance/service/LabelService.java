package com.personal.finance.service;

import com.personal.finance.dto.LabelItem;
import com.personal.finance.dto.TransactionView;
import com.personal.finance.repository.FinancialTransactionRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LabelService {

    private final FinancialTransactionRepository ledger;

    public LabelService(FinancialTransactionRepository ledger) {
        this.ledger = ledger;
    }

    /** Events the server has not received yet are reported back so the phone can try again after uploading them. */
    @Transactional
    public LabelItem.Result apply(Long deviceId, List<LabelItem> items) {
        int updated = 0;
        List<String> missing = new ArrayList<>();
        for (LabelItem item : items) {
            int rows = ledger.applyLabel(deviceId, item.localEventId(), clean(item.category()), clean(item.label()), splitsOf(item));
            if (rows == 0) {
                missing.add(item.localEventId());
            } else {
                updated++;
            }
        }
        return new LabelItem.Result(updated, missing);
    }

    private static String splitsOf(LabelItem item) {
        if (item.splits() == null) {
            return null;
        }
        List<TransactionView.Split> parts = new ArrayList<>();
        for (LabelItem.Part p : item.splits()) {
            String category = clean(p.category());
            if (category != null) {
                parts.add(new TransactionView.Split(category, p.amount()));
            }
        }
        return Splits.encode(parts);
    }

    /** Free text is stored as plain text only: control characters are dropped and blank means none. */
    static String clean(String value) {
        if (value == null) {
            return null;
        }
        String text = value.replaceAll("\\p{Cntrl}", " ").trim();
        return text.isEmpty() ? null : text;
    }
}
