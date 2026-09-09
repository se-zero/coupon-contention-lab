package com.experiment.coupon.api;

import com.experiment.coupon.api.dto.CouponResponse;
import com.experiment.coupon.api.dto.IssueRequest;
import com.experiment.coupon.api.dto.IssueResponse;
import com.experiment.coupon.domain.IssueResult;
import com.experiment.coupon.service.CouponIssueService;
import com.experiment.coupon.service.CouponQueryService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/coupons")
public class CouponController {

    private final CouponIssueService issueService;
    private final CouponQueryService queryService;
    private final String instanceId;

    public CouponController(CouponIssueService issueService,
                            CouponQueryService queryService,
                            @Value("${coupon.instance-id}") String instanceId) {
        this.issueService = issueService;
        this.queryService = queryService;
        this.instanceId = instanceId;
    }

    // 발급 요청 (FR-01, FR-02, FR-05) — 실험 A 대상
    @PostMapping("/{couponId}/issue")
    public ResponseEntity<IssueResponse> issue(@PathVariable long couponId,
                                               @Valid @RequestBody IssueRequest request) {
        IssueResult result = issueService.issue(couponId, request.userId());
        return ResponseEntity
                .status(result.status())
                .body(IssueResponse.of(result, couponId, request.userId(),
                        issueService.strategyType(), instanceId));
    }

    // 쿠폰 상세 (FR-03) — 실험 B 대상
    @GetMapping("/{couponId}")
    public ResponseEntity<CouponResponse> findOne(@PathVariable long couponId) {
        return queryService.findById(couponId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // 쿠폰 목록 (FR-04) — 실험 B 대상
    @GetMapping
    public List<CouponResponse> findAll() {
        return queryService.findAll();
    }
}
