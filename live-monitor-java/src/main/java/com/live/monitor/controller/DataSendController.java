package com.live.monitor.controller;

import com.live.monitor.dto.DataSendExecutePayload;
import com.live.monitor.dto.DataSendPreviewPayload;
import com.live.monitor.dto.DataSendPreviewResponse;
import com.live.monitor.dto.DataSendResultResponse;
import com.live.monitor.service.DataSendService;
import java.util.List;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/data-send")
public class DataSendController {
    private final DataSendService dataSendService;

    public DataSendController(DataSendService dataSendService) {
        this.dataSendService = dataSendService;
    }

    @PostMapping("/preview")
    public DataSendPreviewResponse preview(@Valid @RequestBody DataSendPreviewPayload payload) {
        return dataSendService.preview(payload);
    }

    @PostMapping("/send")
    public DataSendResultResponse executeSend(@Valid @RequestBody DataSendExecutePayload payload) {
        return dataSendService.executeSend(payload);
    }

    @GetMapping("/history")
    public List<DataSendResultResponse> history() {
        return dataSendService.listHistory();
    }
}
