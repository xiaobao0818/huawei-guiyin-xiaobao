package com.attribution.api.controller;

import com.attribution.api.model.ReportRequest;
import com.attribution.api.model.ReportResponse;
import com.attribution.common.dto.R;
import com.attribution.common.entity.EventTask;
import com.attribution.common.repository.EventTaskRepository;
import com.attribution.core.engine.AttributionEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final AttributionEngine attributionEngine;
    private final EventTaskRepository eventTaskRepo;
    private final ObjectMapper objectMapper;

    public ReportController(AttributionEngine attributionEngine,
                           EventTaskRepository eventTaskRepo,
                           ObjectMapper objectMapper) {
        this.attributionEngine = attributionEngine;
        this.eventTaskRepo = eventTaskRepo;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/report")
    public R<ReportResponse> report(@Valid @RequestBody ReportRequest request,
                                     HttpServletRequest httpRequest,
                                     @RequestParam(value = "async", defaultValue = "false") boolean async) {
        log.info("收到上报: game={}, platform={}, event={}, oaid={}, async={}",
                request.getGameId(), request.getPlatform(), request.getEvent(),
                request.getDevice() != null ? request.getDevice().getOaid() : "null", async);

        AttributionEngine.ReportRequest engineReq = buildEngineRequest(request, httpRequest);

        // Async mode: enqueue as EventTask and return immediately
        if (async) {
            try {
                EventTask task = new EventTask();
                task.setGameId(request.getGameId());
                task.setRequestJson(objectMapper.writeValueAsString(engineReq));
                task.setStatus("pending");
                eventTaskRepo.save(task);
                return R.ok(new ReportResponse("accepted", null, null, "任务已入队，异步处理中"));
            } catch (Exception e) {
                log.error("异步任务创建失败", e);
                // Fall through to sync processing
            }
        }

        // Sync mode: process immediately
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

    private AttributionEngine.ReportRequest buildEngineRequest(ReportRequest request,
                                                                HttpServletRequest httpRequest) {
        AttributionEngine.ReportRequest engineReq = new AttributionEngine.ReportRequest();
        engineReq.setGameId(request.getGameId());
        engineReq.setPlatform(request.getPlatform());
        engineReq.setEvent(request.getEvent());
        engineReq.setEventParams(request.getEventParams());
        engineReq.setFingerprint(request.getFingerprint());
        engineReq.setDebugMode(Boolean.TRUE.equals(httpRequest.getAttribute("debug_mode")));

        if (request.getDevice() != null) {
            AttributionEngine.DeviceInfo di = new AttributionEngine.DeviceInfo();
            di.setOaid(request.getDevice().getOaid());
            di.setGaid(request.getDevice().getGaid());
            di.setIdfa(request.getDevice().getIdfa());
            engineReq.setDevice(di);
        }

        if (request.getApp() != null) {
            AttributionEngine.AppInfo ai = new AttributionEngine.AppInfo();
            ai.setVersion(request.getApp().getVersion());
            engineReq.setApp(ai);
        }

        engineReq.setTs(request.getTs());
        return engineReq;
    }
}
