package com.attribution.api.controller;

import com.attribution.api.model.ReportRequest;
import com.attribution.api.model.ReportResponse;
import com.attribution.common.dto.R;
import com.attribution.core.engine.AttributionEngine;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final AttributionEngine attributionEngine;

    public ReportController(AttributionEngine attributionEngine) {
        this.attributionEngine = attributionEngine;
    }

    @PostMapping("/report")
    public R<ReportResponse> report(@Valid @RequestBody ReportRequest request) {
        log.info("收到上报: game={}, platform={}, event={}, oaid={}",
                request.getGameId(), request.getPlatform(), request.getEvent(),
                request.getDevice() != null ? request.getDevice().getOaid() : "null");

        AttributionEngine.ReportRequest engineReq = new AttributionEngine.ReportRequest();
        engineReq.setGameId(request.getGameId());
        engineReq.setPlatform(request.getPlatform());
        engineReq.setEvent(request.getEvent());
        engineReq.setEventParams(request.getEventParams());
        engineReq.setFingerprint(request.getFingerprint());

        if (request.getDevice() != null) {
            AttributionEngine.DeviceInfo di = new AttributionEngine.DeviceInfo();
            di.setOaid(request.getDevice().getOaid());
            engineReq.setDevice(di);
        }

        if (request.getApp() != null) {
            AttributionEngine.AppInfo ai = new AttributionEngine.AppInfo();
            ai.setVersion(request.getApp().getVersion());
            engineReq.setApp(ai);
        }

        engineReq.setTs(request.getTs());

        AttributionEngine.ProcessResult result = attributionEngine.process(engineReq);

        if (result.isSuccess()) {
            ReportResponse resp = new ReportResponse(
                    result.getStatus(),
                    result.getAttributionId(),
                    result.getConversionType(),
                    "处理成功"
            );
            return R.ok(resp);
        } else {
            return R.fail(result.getError());
        }
    }
}
