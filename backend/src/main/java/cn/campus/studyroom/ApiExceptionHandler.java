package cn.campus.studyroom;

import static cn.campus.studyroom.ApiTypes.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 统一错误响应；数据库/框架内部细节不作为用户消息返回。 */
@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /** 显式业务异常保留400/401/403/404/409分类。 */
  @ExceptionHandler(BusinessError.class)
  public ResponseEntity<Result> business(BusinessError ex) {
    return ResponseEntity.status(ex.status).body(new Result(false, null, ex.getMessage()));
  }

  /** Bean输入、未知字段、格式错误都返回400且不泄露密码输入。 */
  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class
  })
  public ResponseEntity<Result> input(Exception ex) {
    return ResponseEntity.badRequest().body(new Result(false, null, "输入字段或格式不合法，请检查必填项及日期时间"));
  }

  /** 唯一键或外键约束失败映射409，覆盖并发注册/房间编号竞争。 */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<Result> constraint(Exception ex) {
    return ResponseEntity.status(409).body(new Result(false, null, "数据冲突，请刷新后重试"));
  }

  /** 锁超时/死锁可重试，不能把失败请求伪装为成功预约。 */
  @ExceptionHandler(PessimisticLockingFailureException.class)
  public ResponseEntity<Result> lock(Exception ex) {
    return ResponseEntity.status(409).body(new Result(false, null, "操作繁忙，请稍后重试"));
  }

  /** 内部故障统一500；日志仅记录异常类别，避免SQL参数、凭据或请求正文泄露。 */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Result> internal(Exception ex) {
    LOG.error("请求处理内部异常，类别={}", ex.getClass().getName());
    return ResponseEntity.status(500).body(new Result(false, null, "服务内部错误，请稍后重试"));
  }
}
