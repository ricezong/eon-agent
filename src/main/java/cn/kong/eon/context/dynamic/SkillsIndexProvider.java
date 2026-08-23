package cn.kong.eon.context.dynamic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 生成 <agent_skills> 动态注入块。
 * 扫描 skills/ 目录，列出可用技能名称和路径。
 */
public class SkillsIndexProvider {

    /**
     * 生成 agent_skills 注入文本。
     * @param skillsDir 技能目录路径，null 则返回 null
     * @return <agent_skills> 块文本，null 表示无内容
     */
    public static String generate(String skillsDir) {
        if (skillsDir == null || skillsDir.isBlank()) return null;
        Path dir = Path.of(skillsDir);
        if (!Files.isDirectory(dir)) return null;

        StringBuilder sb = new StringBuilder("<agent_skills>\n");
        int[] count = {0};

        try (Stream<Path> stream = Files.list(dir)) {
            var entries = stream
                    .filter(Files::isDirectory)
                    .filter(p -> {
                        Path skillMd = p.resolve("SKILL.md");
                        return Files.exists(skillMd);
                    })
                    .sorted()
                    .toList();

            if (entries.isEmpty()) {
                return null;
            }

            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                sb.append("- ").append(name).append(" (").append(entry).append(")\n");
                count[0]++;
            }
        } catch (IOException e) {
            return null;
        }

        if (count[0] == 0) return null;
        sb.append("</agent_skills>");
        return sb.toString();
    }
}
