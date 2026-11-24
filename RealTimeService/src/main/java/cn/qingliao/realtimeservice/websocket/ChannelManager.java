package cn.qingliao.realtimeservice.websocket;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.util.concurrent.ConcurrentHashMap;

/** 当前节点的一人一连接索引；跨节点定位留到一致性哈希章节。 */
public final class ChannelManager {
    private static final ConcurrentHashMap<String, Channel> USER_CHANNEL_MAP =
            new ConcurrentHashMap<>();
    private static final AttributeKey<Runnable> ON_DISCONNECT =
            AttributeKey.valueOf("infinitechat.disconnected");
    private static final AttributeKey<String> TOKEN = AttributeKey.valueOf("infinitechat.token");
    private static final AttributeKey<String> USER_ID = AttributeKey.valueOf("infinitechat.userId");

    private ChannelManager() {}

    /** 握手成功后发布连接，替换旧连接；身份由鉴权处理器写入。 */
    public static void bind(String userId, Channel channel) {
        bind(userId, channel, () -> {});
    }

    public static synchronized void bind(String userId, Channel channel, Runnable onDisconnect) {
        channel.attr(ON_DISCONNECT).set(onDisconnect);
        channel.attr(USER_ID).set(userId);
        Channel old = USER_CHANNEL_MAP.put(userId, channel);
        // 注册关闭监听
        channel.closeFuture().addListener(future -> unbind(channel));
        // close执行旧链接的关闭监听函数
        if (old != null && old != channel) {
            old.close();
        }
    }

    /** 旧连接晚到的关闭回调不能删除用户刚建立的新连接。 */
    public static void unbind(Channel channel) {
        String userId = channel.attr(USER_ID).getAndSet(null);
        if (userId != null) {
            boolean removed = USER_CHANNEL_MAP.remove(userId, channel);
            Runnable callback = channel.attr(ON_DISCONNECT).getAndSet(null);
            if (removed && callback != null) callback.run();
        }
    }

    public static void authenticate(Channel channel, String userId) {
        channel.attr(USER_ID).set(userId);
    }

    public static Channel getChannelByUserId(String userId) {
        return USER_CHANNEL_MAP.get(userId);
    }

    public static String getUserIdByChannel(Channel channel) {
        return channel.attr(USER_ID).get();
    }

    public static void token(Channel channel, String token) {
        channel.attr(TOKEN).set(token);
    }

    public static String token(Channel channel) {
        return channel.attr(TOKEN).get();
    }
}
