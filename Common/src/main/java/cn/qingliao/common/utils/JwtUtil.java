package cn.qingliao.common.utils;

import cn.qingliao.common.constant.CommonConstant;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;

import java.security.Key;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/** JWT工具类（优化点：完善异常日志、统一过期时间配置） */
@Slf4j
public final class JwtUtil {

    /** 生成 HS256 签名的 JWT；Redis 当前凭证由 createJwt 另行保存。 */
    public static String generate(String userId, long timeout, TimeUnit unit) {
        Date now = new Date();
        // 设置过期时间
        Date expiration = new Date(now.getTime() + unit.toMillis(timeout));
        return Jwts.builder()
                .setId(java.util.UUID.randomUUID().toString())
                .setSubject(userId) // 存储用户唯一标识（如手机号/用户ID）
                .setIssuedAt(now) // 签发时间
                .setExpiration(expiration) // JWT 自身有效期；调用方还需配套配置 Redis 凭证的 TTL
                .signWith(getSignInKey(), SignatureAlgorithm.HS256) // 签名算法
                .compact();
    }

    /** 从常量读取签名密钥，后续可外置配置。 */
    public static Key getSignInKey() {
        if (CommonConstant.TOKEN_SECRET_KEY == null || CommonConstant.TOKEN_SECRET_KEY.isBlank()) {
            throw new IllegalStateException("请配置 JWT_SECRET_BASE64");
        }
        byte[] keyBytes = Decoders.BASE64.decode(CommonConstant.TOKEN_SECRET_KEY);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /** 验签并校验有效期，失败返回 null；退出或替换状态还需核对 Redis。 */
    public static Claims parse(String token) {
        if (StringUtils.isEmpty(token)) {
            log.warn("解析JWT失败：token 为空");
            return null;
        }
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(getSignInKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (ExpiredJwtException e) {
            log.debug("JWT 已过期");
            return null;
        } catch (JwtException e) {
            log.debug("JWT 签名无效或格式错误");
            return null;
        } catch (Exception e) {
            log.error("JWT 解析发生未知错误：", e);
            return null;
        }
    }
}
