package cn.qingliao.common.model.dto;

/** 资料访问只要求有效成员；个人资料仅可进入本人助手会话。 */
public record KnowledgeSession(boolean accessible, boolean assistant) {}
