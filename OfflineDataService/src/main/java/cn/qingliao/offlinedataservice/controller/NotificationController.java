package cn.qingliao.offlinedataservice.controller;

import cn.qingliao.common.common.*;
import cn.qingliao.common.exception.BusinessException;
import cn.qingliao.common.security.CurrentUser;
import cn.qingliao.common.utils.WireJson;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/message/notifications")
@RequiredArgsConstructor
public class NotificationController {
    private final JdbcTemplate jdbc;

    @GetMapping
    public BaseResponse<List<Object>> list(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {
        if (offset < 0 || offset > 100000 || limit < 1 || limit > 100)
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        List<Object> result =
                jdbc.query(
                        "SELECT payload FROM system_notification WHERE receiver_id=? ORDER BY"
                                + " created_time DESC,message_id DESC LIMIT ? OFFSET ?",
                        (row, index) -> WireJson.read(row.getString("payload")),
                        CurrentUser.id(),
                        limit,
                        offset);
        return ResultUtils.success(result);
    }

    @PostMapping("/{messageId}/read")
    public BaseResponse<Boolean> read(@PathVariable String messageId) {
        return ResultUtils.success(
                jdbc.update(
                                "UPDATE system_notification SET read_flag=1 WHERE message_id=? AND"
                                        + " receiver_id=?",
                                messageId,
                                CurrentUser.id())
                        > 0);
    }
}
