package com.experiment.coupon.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 발급 요청
 *
 * 인증은 실험 범위 밖이므로 user_id 를 직접 받는다. (PROJECT_BRIEF FR-09)
 */
public record IssueRequest(@NotNull Long userId) {
}
