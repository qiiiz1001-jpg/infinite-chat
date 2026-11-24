package cn.qingliao.userservice.model.dto.request;

@lombok.Data
public class CreateGroupRequest implements java.io.Serializable {
    @java.io.Serial private static final long serialVersionUID = 1L;
    private Long creatorId;
    private String name;
    private java.util.List<Long> memberIds;
}
