package cn.qingliao.userservice.consumer;

import cn.hutool.json.JSONUtil;
import cn.qingliao.userservice.constant.FriendApplicationStatusEnum;
import cn.qingliao.userservice.model.dto.FriendRequestExpirationEvent;
import cn.qingliao.userservice.model.entity.ApplyFriend;
import cn.qingliao.userservice.service.ApplyFriendService;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class FriendRequestExpirationExecutor {
    private final ApplyFriendService applyFriendService;

    public FriendRequestExpirationExecutor(ApplyFriendService applyFriendService) {
        this.applyFriendService = applyFriendService;
    }

    /** 数据库兜底扫描，补偿进程退出或延迟事件丢失；状态与时间条件保证重复执行安全。 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
    public void repairExpiredRequests() {
        applyFriendService.update(
                new LambdaUpdateWrapper<ApplyFriend>()
                        .set(ApplyFriend::getStatus, FriendApplicationStatusEnum.EXPIRED.getCode())
                        .le(
                                ApplyFriend::getCreatedTime,
                                java.time.LocalDateTime.now().minusHours(24))
                        .in(
                                ApplyFriend::getStatus,
                                FriendApplicationStatusEnum.UNREAD.getCode(),
                                FriendApplicationStatusEnum.READ.getCode()));
    }

    @KafkaListener(
            topics = "friend-request-expiration-topic",
            groupId = "friend-request-executor-group",
            concurrency = "3" // 支持并发处理
            )
    public void executeExpiration(String message) {
        try {
            log.info("收到好友申请过期事件: {}", message);
            // 1. 解析事件
            FriendRequestExpirationEvent event =
                    JSONUtil.toBean(message, FriendRequestExpirationEvent.class);
            Long applyFriendId = event.getApplyFriendId();
            // 2. 查询好友申请记录
            ApplyFriend applyFriend = applyFriendService.getById(applyFriendId);
            if (applyFriend == null) {
                log.warn("好友申请不存在，申请ID: {}", applyFriendId);
                return;
            }
            java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusHours(24);
            if (applyFriend.getCreatedTime().isAfter(cutoff)) return;
            // 3. 检查当前状态
            Integer currentStatus = applyFriend.getStatus();
            if (currentStatus.equals(FriendApplicationStatusEnum.EXPIRED.getCode())) {
                // 已经是过期状态，幂等性处理
                log.info("好友申请已是过期状态，跳过处理，申请ID: {}", applyFriendId);
                return;
            }
            if (currentStatus.equals(FriendApplicationStatusEnum.ACCEPTED.getCode())) {
                // 已通过，不应标记为过期
                log.info("好友申请已通过，不应标记为过期，申请ID: {}", applyFriendId);
                return;
            }
            if (currentStatus.equals(FriendApplicationStatusEnum.REJECTED.getCode())) {
                // 已拒绝，不应标记为过期
                log.info("好友申请已拒绝，不应标记为过期，申请ID: {}", applyFriendId);
                return;
            }
            // 4. 更新状态为EXPIRED（只更新UNREAD和READ状态的申请）
            LambdaUpdateWrapper<ApplyFriend> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper
                    .set(ApplyFriend::getStatus, FriendApplicationStatusEnum.EXPIRED.getCode())
                    .eq(ApplyFriend::getApplyFriendId, applyFriendId)
                    .le(ApplyFriend::getCreatedTime, cutoff)
                    .in(
                            ApplyFriend::getStatus,
                            FriendApplicationStatusEnum.UNREAD.getCode(),
                            FriendApplicationStatusEnum.READ.getCode());
            boolean updated = applyFriendService.update(updateWrapper);
            if (updated) {
                log.info("好友申请已标记为过期，申请ID: {}", applyFriendId);
            } else {
                log.warn("好友申请状态更新失败（可能已被处理），申请ID: {}", applyFriendId);
            }
        } catch (Exception e) {
            log.error("处理好友申请过期事件失败: {}, 错误: {}", message, e.getMessage(), e);
        }
    }
}
