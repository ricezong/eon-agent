package cn.kong.eon.api;

import cn.kong.eon.api.dto.FileContent;
import cn.kong.eon.api.dto.FileMeta;
import cn.kong.eon.api.service.FileService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 会话工作区文件读取。路径越界由 {@link FileService} 拦截。
 * 三个端点职责分离：元信息查询、文本预览、原始字节输出。
 */
@RestController
@RequestMapping("/api")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    /** 文件元信息：体积、MIME、是否二进制、编码。前端据此选择渲染器。 */
    @GetMapping("/sessions/{sessionId}/files/meta")
    public FileMeta meta(@PathVariable String sessionId,
                         @RequestParam(name = "path") String path) {
        return fileService.meta(sessionId, path);
    }

    /** 文件文本预览，超过上限时截断。 */
    @GetMapping("/sessions/{sessionId}/files/content")
    public FileContent content(@PathVariable String sessionId,
                               @RequestParam(name = "path") String path) {
        return fileService.content(sessionId, path);
    }

    /**
     * 原始字节输出。download=1 时强制下载；否则内联展示，
     * 对可执行文档（HTML / SVG）附加 CSP sandbox，避免其在同源上下文执行脚本。
     */
    @GetMapping("/sessions/{sessionId}/files/raw")
    public ResponseEntity<InputStreamResource> raw(@PathVariable String sessionId,
                                                   @RequestParam(name = "path") String path,
                                                   @RequestParam(name = "download", defaultValue = "false") boolean download) {
        FileService.FileHandle handle = fileService.raw(sessionId, path);
        FileMeta meta = handle.meta();

        InputStream in;
        try {
            in = Files.newInputStream(handle.file());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(meta.mime()));
        headers.setContentLength(meta.sizeBytes());
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                (download ? "attachment" : "inline") + "; filename*=UTF-8''" + encodedFileName(meta.name()));
        headers.set("X-Content-Type-Options", "nosniff");
        if (!download && isActiveDocument(meta.mime())) {
            headers.set("Content-Security-Policy", "sandbox");
        }

        return new ResponseEntity<>(new InputStreamResource(in), headers, HttpStatus.OK);
    }

    /** 可被浏览器当作文档执行脚本的类型，内联时必须沙箱化。 */
    private static boolean isActiveDocument(String mime) {
        return mime != null
                && (mime.startsWith("text/html") || mime.startsWith("image/svg+xml"));
    }

    private static String encodedFileName(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
