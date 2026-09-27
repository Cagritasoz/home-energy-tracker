package com.cagritasoz.user_service.service;

import com.cagritasoz.user_service.repository.OutboxRepository;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class OutboxServiceTest {

    @Mock
    private OutboxRepository outboxRepository;

    @InjectMocks
    private OutboxService outboxService;
}
