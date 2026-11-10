package com.personal.finance;

import static com.personal.finance.TestSupport.event;
import static com.personal.finance.TestSupport.ingest;
import static com.personal.finance.TestSupport.register;
import static com.personal.finance.TestSupport.uniqueSourceId;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.finance.TestSupport.Device;
import com.personal.finance.entity.RawFinancialEvent;
import com.personal.finance.repository.OutboxEventRepository;
import com.personal.finance.repository.RawFinancialEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** A failing database write must never be acknowledged as stored. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DatabaseOutageTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    RawFinancialEventRepository events;

    @MockitoBean
    OutboxEventRepository outbox;

    @Test
    void anOutageOnLookupReturns503AndStoresNothing() throws Exception {
        Device device = register(mvc);
        when(events.findIdBySource(any(), any(), any())).thenThrow(new DataAccessResourceFailureException("db down"));

        mvc.perform(ingest(device, event("l1", uniqueSourceId(), "Rs 1 debited")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"));

        verify(events, never()).saveAndFlush(any(RawFinancialEvent.class));
    }

    @Test
    void anOutageOnWriteReturns503AndNoAcknowledgement() throws Exception {
        Device device = register(mvc);
        when(events.findIdBySource(any(), any(), any())).thenReturn(java.util.Optional.empty());
        when(events.saveAndFlush(any(RawFinancialEvent.class)))
                .thenThrow(new DataAccessResourceFailureException("write failed"));

        mvc.perform(ingest(device, event("l2", uniqueSourceId(), "Rs 1 debited")))
                .andExpect(status().isServiceUnavailable());
    }
}
