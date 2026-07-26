package cloud.bamsongi.albammate.global.exception;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpSessionRequiredException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringJUnitConfig(GlobalExceptionHandlerTest.MockMvcTestConfiguration.class)
@WebAppConfiguration
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MockMvc mockMvc =
            MockMvcBuilders.standaloneSetup(new MockMvcTestController())
                    .setControllerAdvice(handler)
                    .build();

    @Autowired private org.springframework.web.context.WebApplicationContext webApplicationContext;

    private MockMvc webAppMockMvc;

    @BeforeEach
    void setUpWebAppMockMvc() {
        webAppMockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void BusinessException은_오류_코드의_HTTP_상태와_메시지로_변환한다() throws Exception {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusinessException(new BusinessException(ErrorCode.ROOM_NOT_FOUND));

        assertEquals(404, response.getStatusCode().value());
        assertErrorBody(response.getBody(), ErrorCode.ROOM_NOT_FOUND);
    }

    @Test
    void Bean_Validation_실패는_상세값을_노출하지_않고_검증_오류로_변환한다() throws Exception {
        BindException exception = new BindException(new Object(), "request");

        ResponseEntity<ApiResponse<Void>> response = handler.handleRequestException(exception);

        assertEquals(400, response.getStatusCode().value());
        assertErrorBody(response.getBody(), ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void Spring_MVC_resolver는_잘못된_JSON을_400_공통_봉투로_변환한다() throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/requests")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"value\":"))
                        .andExpect(status().isBadRequest())
                        .andReturn();

        assertErrorJson(result, ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void Spring_MVC_resolver는_허용되지_않은_메서드와_Allow_헤더를_변환한다() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/requests"))
                        .andExpect(status().isMethodNotAllowed())
                        .andReturn();

        assertEquals("POST", result.getResponse().getHeader(HttpHeaders.ALLOW));
        assertErrorJson(result, ErrorCode.METHOD_NOT_ALLOWED);
    }

    @Test
    void Spring_MVC_resolver는_지원하지_않는_미디어_타입과_Accept_헤더를_변환한다() throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/requests")
                                        .contentType(MediaType.TEXT_PLAIN)
                                        .content("plain text"))
                        .andExpect(status().isUnsupportedMediaType())
                        .andReturn();

        assertEquals(
                MediaType.APPLICATION_JSON_VALUE,
                result.getResponse().getHeader(HttpHeaders.ACCEPT));
        assertErrorJson(result, ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void Spring_MVC_resolver는_Accept_재협상_실패를_공통_406_봉투로_변환한다() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/responses").accept(MediaType.TEXT_PLAIN))
                        .andExpect(status().isNotAcceptable())
                        .andReturn();

        assertErrorJson(result, ErrorCode.NOT_ACCEPTABLE);
    }

    @Test
    void 실제_MockMvc_요청의_미매핑_URL은_RESOURCE_NOT_FOUND_404_공통_봉투로_변환한다() throws Exception {
        MvcResult result =
                webAppMockMvc
                        .perform(get("/missing-endpoint"))
                        .andExpect(status().isNotFound())
                        .andReturn();

        assertErrorJson(result, ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 실제_MockMvc_요청의_누락된_정적_리소스는_RESOURCE_NOT_FOUND_404_공통_봉투로_변환한다() throws Exception {
        MvcResult result =
                webAppMockMvc
                        .perform(get("/static/missing-resource.txt"))
                        .andExpect(status().isNotFound())
                        .andReturn();

        assertErrorJson(result, ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 응답_협상_실패_예외는_미디어_타입과_Accept_헤더를_공통_봉투로_변환한다() {
        HttpMediaTypeNotAcceptableException exception =
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<ApiResponse<Void>> response = handler.handleNotAcceptable(exception);

        assertEquals(406, response.getStatusCode().value());
        assertEquals(
                exception.getHeaders().get(HttpHeaders.ACCEPT),
                response.getHeaders().get(HttpHeaders.ACCEPT));
        assertErrorBody(response.getBody(), ErrorCode.NOT_ACCEPTABLE);
    }

    @Test
    void 메서드와_미디어_타입_예외의_프로토콜_헤더를_응답에_보존한다() {
        HttpRequestMethodNotSupportedException methodException =
                new HttpRequestMethodNotSupportedException("GET", List.of("POST"));
        HttpMediaTypeNotSupportedException mediaTypeException =
                new HttpMediaTypeNotSupportedException(
                        MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<ApiResponse<Void>> methodResponse =
                handler.handleMethodNotAllowed(methodException);
        ResponseEntity<ApiResponse<Void>> mediaTypeResponse =
                handler.handleUnsupportedMediaType(mediaTypeException);

        assertEquals(
                methodException.getHeaders().get(HttpHeaders.ALLOW),
                methodResponse.getHeaders().get(HttpHeaders.ALLOW));
        assertEquals(
                mediaTypeException.getHeaders().get(HttpHeaders.ACCEPT),
                mediaTypeResponse.getHeaders().get(HttpHeaders.ACCEPT));
        assertErrorBody(methodResponse.getBody(), ErrorCode.METHOD_NOT_ALLOWED);
        assertErrorBody(mediaTypeResponse.getBody(), ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void 핸들러와_리소스_미존재_예외는_RESOURCE_NOT_FOUND로_변환한다() {
        NoHandlerFoundException noHandlerException =
                new NoHandlerFoundException("GET", "/missing", new HttpHeaders());
        NoResourceFoundException noResourceException =
                new NoResourceFoundException(HttpMethod.GET, "static resource", "missing");

        ResponseEntity<ApiResponse<Void>> noHandlerResponse =
                handler.handleNoHandlerFound(noHandlerException);
        ResponseEntity<ApiResponse<Void>> noResourceResponse =
                handler.handleNoResourceFound(noResourceException);

        assertErrorBody(noHandlerResponse.getBody(), ErrorCode.RESOURCE_NOT_FOUND);
        assertErrorBody(noResourceResponse.getBody(), ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 세션_필수_예외는_인증_필요_오류로_변환한다() throws Exception {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleMissingSession(new HttpSessionRequiredException("session"));

        assertEquals(401, response.getStatusCode().value());
        assertErrorBody(response.getBody(), ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void 처리하지_않은_예외는_원인_메시지_없이_서버_오류로_변환한다() throws Exception {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleUnhandledException(
                        new IllegalStateException("password=secret, userId=42"));

        assertEquals(500, response.getStatusCode().value());
        assertErrorBody(response.getBody(), ErrorCode.INTERNAL_SERVER_ERROR);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response.getBody()));
        assertTrue(json.toString().contains(ErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
        assertTrue(!json.toString().contains("password"));
        assertTrue(!json.toString().contains("userId"));
        assertTrue(!json.toString().contains("secret"));
    }

    @Test
    void 실제_MockMvc_요청의_예기치_않은_예외는_500_공통_봉투로_변환하고_민감정보를_로그하지_않는다() throws Exception {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            MvcResult result =
                    webAppMockMvc
                            .perform(get("/unexpected"))
                            .andExpect(status().isInternalServerError())
                            .andReturn();

            assertErrorJson(result, ErrorCode.INTERNAL_SERVER_ERROR);

            ILoggingEvent event =
                    appender.list.stream()
                            .filter(loggingEvent -> loggingEvent.getLevel() == Level.ERROR)
                            .findFirst()
                            .orElseThrow();
            assertTrue(
                    event.getFormattedMessage()
                            .contains("exceptionType=java.lang.IllegalStateException"));
            assertFalse(event.getFormattedMessage().contains("password=secret"));
            assertFalse(event.getFormattedMessage().contains("userId=42"));
            assertFalse(event.getFormattedMessage().contains("database-token"));

            IThrowableProxy throwableProxy = event.getThrowableProxy();
            assertNotNull(throwableProxy);
            assertNull(throwableProxy.getMessage());
            assertNull(throwableProxy.getCause());
            String stackTrace =
                    Arrays.stream(throwableProxy.getStackTraceElementProxyArray())
                            .map(StackTraceElementProxy::toString)
                            .collect(Collectors.joining("\n"));
            assertTrue(stackTrace.contains("MockMvcTestController.unexpected"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void 로그용_예외는_원본_스택과_클래스명만_보존한다() {
        IllegalStateException source =
                new IllegalStateException(
                        "password=secret", new IllegalArgumentException("userId=42"));

        Throwable sanitized = GlobalExceptionHandler.sanitizeForLogging(source);

        assertEquals(source.getClass().getName(), sanitized.toString());
        assertNull(sanitized.getMessage());
        assertNull(sanitized.getCause());
        assertArrayEquals(source.getStackTrace(), sanitized.getStackTrace());
    }

    @Test
    void 인증과_권한_실패용_예외는_공통_코드로_변환한다() {
        assertEquals(
                401,
                handler.handleBusinessException(new UnauthenticatedException())
                        .getStatusCode()
                        .value());
        assertEquals(
                403,
                handler.handleBusinessException(new ForbiddenException()).getStatusCode().value());
        assertEquals(
                ErrorCode.CSRF_TOKEN_INVALID.getCode(),
                handler.handleBusinessException(new CsrfTokenInvalidException()).getBody().code());
    }

    private void assertErrorBody(ApiResponse<Void> body, ErrorCode expected) {
        assertEquals(expected.getStatus(), body.status());
        assertEquals(expected.getCode(), body.code());
        assertEquals(expected.getMessage(), body.message());
        assertTrue(body.data() == null);
    }

    private void assertErrorJson(MvcResult result, ErrorCode expected) throws Exception {
        String contentType = result.getResponse().getContentType();
        assertNotNull(contentType);
        assertTrue(
                MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(contentType)));
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(expected.getStatus(), json.get("status").intValue());
        assertEquals(expected.getCode(), json.get("code").textValue());
        assertEquals(expected.getMessage(), json.get("message").textValue());
        assertTrue(json.has("data"));
        assertTrue(json.get("data").isNull());
    }

    @RestController
    private static final class MockMvcTestController {

        @PostMapping(
                path = "/requests",
                consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
        Map<String, Object> request(@RequestBody Map<String, Object> body) {
            return body;
        }

        @GetMapping(path = "/responses", produces = MediaType.APPLICATION_JSON_VALUE)
        Map<String, String> response() {
            return Map.of("result", "ok");
        }

        @GetMapping(path = "/unexpected", produces = MediaType.APPLICATION_JSON_VALUE)
        Map<String, String> unexpected() {
            throw new IllegalStateException("password=secret, userId=42, database-token=hidden");
        }
    }

    @Configuration
    @EnableWebMvc
    static class MockMvcTestConfiguration implements WebMvcConfigurer {

        @Bean
        MockMvcTestController mockMvcTestController() {
            return new MockMvcTestController();
        }

        @Bean
        GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }

        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/static/**").addResourceLocations("classpath:/static/");
        }
    }
}
