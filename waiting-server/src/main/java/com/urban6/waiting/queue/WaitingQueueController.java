package com.urban6.waiting.queue;

import com.urban6.waiting.queue.WaitingQueueService.Ticket;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/waiting-queue")
@RequiredArgsConstructor
public class WaitingQueueController {

    private final WaitingQueueService waitingQueueService;

    @PostMapping
    public ResponseEntity<Ticket> enqueue() {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(waitingQueueService.enqueue());
    }
}
