package cn.qingliao.userservice.agent;

import cn.qingliao.common.model.dto.AgentActions.Action;

import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class AgentActionRepository {
    private final JdbcTemplate jdbc;
    private final BeanPropertyRowMapper<Action> mapper = new BeanPropertyRowMapper<>(Action.class);

    public AgentActionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public Action find(long id) {
        return jdbc.query("SELECT * FROM agent_action WHERE action_id=?", mapper, id).stream()
                .findFirst()
                .orElse(null);
    }

    public Action byKey(long owner, String key) {
        return jdbc
                .query(
                        "SELECT * FROM agent_action WHERE owner_id=? AND request_key=?",
                        mapper,
                        owner,
                        key)
                .stream()
                .findFirst()
                .orElse(null);
    }

    public List<Action> list(long owner) {
        return jdbc.query(
                "SELECT * FROM agent_action WHERE owner_id=? ORDER BY created_at DESC,action_id"
                        + " DESC LIMIT 100",
                mapper,
                owner);
    }

    public List<Action> reminders(long owner) {
        return jdbc.query(
                "SELECT * FROM agent_action WHERE owner_id=? AND kind='REMINDER' AND status='SENT'"
                        + " ORDER BY message_time DESC, action_id DESC LIMIT 100",
                mapper,
                owner);
    }

    public List<Action> due(long now) {
        return jdbc.query(
                "SELECT * FROM agent_action WHERE status='PENDING' AND next_attempt_at<=? ORDER BY"
                        + " next_attempt_at LIMIT 100",
                mapper,
                now);
    }

    public void insert(Action a) {
        jdbc.update(
                "INSERT INTO agent_action"
                    + " (action_id,owner_id,session_id,request_key,kind,content,execute_at,status,version,message_id,store_sent,push_sent,attempts,next_attempt_at,lease_until,lease_token,last_error,created_at,updated_at)"
                    + " VALUES (?,?,?,?,?,?,?,'DRAFT',0,?,false,false,0,0,0,'','',?,?)",
                a.getActionId(),
                a.getOwnerId(),
                a.getSessionId(),
                a.getRequestKey(),
                a.getKind(),
                a.getContent(),
                a.getExecuteAt(),
                a.getMessageId(),
                a.getCreatedAt(),
                a.getCreatedAt());
    }
}
