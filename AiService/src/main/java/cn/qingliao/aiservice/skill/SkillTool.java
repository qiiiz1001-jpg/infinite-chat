package cn.qingliao.aiservice.skill;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import jakarta.annotation.PostConstruct;

import lombok.extern.slf4j.Slf4j;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Slf4j
public class SkillTool {

    private final Map<String, String> bodies = new LinkedHashMap<>();

    private String catalog = "";

    /** 启动扫描技能，分别保存每轮清单 L1 与按需正文 L2。 */
    @PostConstruct
    public void load() {
        StringBuilder sb =
                new StringBuilder("## 可用技能（Skills）\n")
                        .append("下列技能封装了本系统特有的操作规程。当用户的意图命中某个技能时，")
                        .append("必须先调用 loadSkill 拿到它的完整说明，再严格按说明执行，不要凭常识直接动手。\n\n");
        try {
            Resource[] resources =
                    new PathMatchingResourcePatternResolver(getClass().getClassLoader())
                            .getResources("classpath*:skills/*/SKILL.md");
            for (Resource resource : resources) {
                String[] parts =
                        resource.getContentAsString(StandardCharsets.UTF_8)
                                .replace("\r\n", "\n")
                                .split("(?m)^---\\s*$", 3);
                String name = parts.length < 3 ? "" : value(parts[1], "name");
                String description = parts.length < 3 ? "" : value(parts[1], "description");
                if (name.isBlank() || description.isBlank()) {
                    log.error(
                            "SKILL.md 缺少 frontmatter 或 name/description，已跳过: {}",
                            resource.getURL());
                    continue;
                }
                String body = parts[2].strip();
                bodies.put(name, body);
                sb.append("- `").append(name).append("`：").append(description).append('\n');
                log.info("Skill 已注册: {}（正文 {} 字符）", name, body.length());
            }
        } catch (Exception e) {
            log.error("Skill 扫描失败，Agent 将以无技能模式运行", e);
        }
        if (!bodies.isEmpty()) {
            catalog = sb.toString();
        }
        log.info(
                "Skill 加载完成，共 {} 个: {}，清单 {} 字符", bodies.size(), bodies.keySet(), catalog.length());
    }

    /** 返回追加到系统提示词的技能名称与描述清单。 */
    public String catalog() {
        return catalog;
    }

    /** 仅返回操作说明，不会自动执行查重、入库或强制步骤顺序。 */
    @Tool("加载一个技能的完整操作说明。当用户的请求命中系统提示词【可用技能】清单里的某个技能时，" + "先调用本工具拿到该技能的详细步骤，再严格按步骤执行。")
    public String loadSkill(@P("技能名，必须是清单里列出的名字，例如 kb-curator") String skillName) {
        String body = bodies.get(skillName);
        if (body == null) {
            log.warn("Skill 不存在: {}", skillName);
            return "没有名为 [" + skillName + "] 的技能。当前可用技能：" + bodies.keySet() + "。请从中选择，或者不使用技能直接回答。";
        }
        log.info("Skill 已加载: {}（{} 字符）", skillName, body.length());
        return "以下是技能 [" + skillName + "] 的完整说明，请严格按其中的步骤执行：\n\n" + body;
    }

    private String value(String frontMatter, String key) {
        for (String line : frontMatter.split("\n")) {
            String t = line.strip();
            if (t.startsWith(key + ":")) {
                return t.substring(key.length() + 1).strip();
            }
        }
        return "";
    }
}
