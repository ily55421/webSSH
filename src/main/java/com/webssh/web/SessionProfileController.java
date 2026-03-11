package com.webssh.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webssh.session.SessionProfileStore;
import com.webssh.session.SshSessionProfile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SSH 会话配置 CRUD 控制器，提供保存、查询、删除、导入导出 SSH 连接配置的 REST API。
 * <p>
 * 所有接口均基于当前登录用户（Principal）进行数据隔离，用户只能操作自己的会话配置。
 * 会话配置包含主机、端口、用户名及加密存储的凭据，获取详情时会解密后返回给前端。
 * </p>
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionProfileController {

    private final SessionProfileStore store;
    private final ObjectMapper objectMapper;

    public SessionProfileController(SessionProfileStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    /**
     * 列出当前用户保存的所有 SSH 会话配置。
     * <p>
     * 返回的列表用于前端展示会话列表，不包含敏感凭据的明文，仅包含基本信息。
     * </p>
     *
     * @param principal 当前登录用户，由 Spring Security 注入
     * @return 当前用户的会话配置列表，可能为空列表
     */
    @GetMapping
    public List<SshSessionProfile> list(Principal principal) {
        return store.list(principal.getName());
    }

    /**
     * 根据 ID 获取指定会话的详细信息。
     * <p>
     * 详情包含解密后的凭据，供建立 SSH 连接时使用。若会话不存在或不属于当前用户，
     * 返回 404，避免通过错误信息泄露资源是否存在。
     * </p>
     *
     * @param id        会话 ID，路径变量
     * @param principal 当前登录用户
     * @return 会话详情（含解密凭据），不存在时返回 404
     */
    @GetMapping("/{id}")
    public ResponseEntity<SshSessionProfile> get(@PathVariable String id, Principal principal) {
        SshSessionProfile profile = store.get(principal.getName(), id);
        if (profile == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(profile);
    }

    /**
     * 新增或更新 SSH 会话配置。
     * <p>
     * 若 profile 带 id 且已存在则更新，否则新增。凭据在 store 层会加密存储，
     * 此处直接透传，由业务层负责加密逻辑。
     * </p>
     *
     * @param profile   会话配置，请求体 JSON 反序列化
     * @param principal 当前登录用户，用于关联数据归属
     * @return 保存后的会话配置（含生成的 id 等）
     */
    @PostMapping
    public SshSessionProfile save(@RequestBody SshSessionProfile profile, Principal principal) {
        return store.save(principal.getName(), profile);
    }

    /**
     * 根据 ID 删除指定会话配置。
     * <p>
     * 删除不存在的会话时返回 404 及 deleted: false，便于前端区分"删除成功"与"资源不存在"。
     * </p>
     *
     * @param id        会话 ID，路径变量
     * @param principal 当前登录用户
     * @return 成功时 {@code { "deleted": true }}，不存在时 404 及 {@code { "deleted": false, "message": "会话不存在" }}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id, Principal principal) {
        HashMap<String, Object> map = new HashMap<>();
        map.put("deleted", false);
        map.put("message", "会话不存在");
        boolean deleted = store.delete(principal.getName(), id);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(map);
        }
        HashMap<String, Object> map2 = new HashMap<>();
        map2.put("deleted", true);
        return ResponseEntity.ok(map2);
    }

    /**
     * 导出当前用户的所有会话配置为 JSON 文件。
     * <p>
     * 导出的 JSON 文件包含所有会话的基本信息和加密的凭据，文件名为 webssh-sessions-[用户名]-[时间戳].json。
     * </p>
     *
     * @param principal 当前登录用户
     * @return JSON 文件的下载响应，Content-Disposition 附件形式
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(Principal principal) {
        try {
            List<SshSessionProfile> profiles = store.list(principal.getName());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(profiles);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

            String filename = "webssh-sessions-"
                    + principal.getName()
                    + "-"
                    + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date())
                    + ".json";

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 从上传的 JSON 文件导入会话配置。
     * <p>
     * 支持批量导入多个会话配置，当导入的会话 ID 与现有会话冲突时，
     * 根据 overlap 参数决定是覆盖还是跳过。
     * </p>
     *
     * @param file      上传的 JSON 文件，包含会话配置数组
     * @param overlap   冲突处理策略：true 表示覆盖现有会话，false 表示跳过冲突会话
     * @param principal 当前登录用户
     * @return 导入结果，包含成功、跳过、失败的数量统计
     */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importSessions(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "overlap", defaultValue = "false") boolean overlap,
            Principal principal) {
        try {
            if (file.isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "上传文件不能为空");
                return ResponseEntity.badRequest().body(error);
            }

            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            List<SshSessionProfile> importedProfiles;
            try {
                importedProfiles = objectMapper.readValue(content,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, SshSessionProfile.class));
            } catch (IOException e) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "JSON 格式错误: " + e.getMessage());
                return ResponseEntity.badRequest().body(error);
            }

            if (importedProfiles == null || importedProfiles.isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "导入文件中没有会话配置");
                return ResponseEntity.badRequest().body(error);
            }

            List<SshSessionProfile> existingProfiles = store.list(principal.getName());
            Map<String, SshSessionProfile> existingMap = new HashMap<>();
            for (SshSessionProfile p : existingProfiles) {
                if (p.getId() != null) {
                    existingMap.put(p.getId(), p);
                }
            }

            int successCount = 0;
            int skipCount = 0;
            int errorCount = 0;
            List<String> errorMessages = new ArrayList<>();

            for (SshSessionProfile profile : importedProfiles) {
                try {
                    if (profile.getId() != null && existingMap.containsKey(profile.getId())) {
                        if (overlap) {
                            // 覆盖现有会话
                            store.save(principal.getName(), profile);
                            successCount++;
                        } else {
                            // 跳过冲突会话
                            skipCount++;
                        }
                    } else {
                        // 新增会话
                        profile.setId(null); // 清除 ID 以便生成新 ID
                        store.save(principal.getName(), profile);
                        successCount++;
                    }
                } catch (Exception e) {
                    errorCount++;
                    String name = profile.getName() != null ? profile.getName() : "未知";
                    errorMessages.add(name + ": " + e.getMessage());
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("total", importedProfiles.size());
            result.put("successCount", successCount);
            result.put("skipCount", skipCount);
            result.put("errorCount", errorCount);
            if (!errorMessages.isEmpty()) {
                result.put("errors", errorMessages);
            }

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "导入失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}
