package cn.campus.studyroom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 校园预约后端入口；使用上海业务时区，持久化时间以本地业务时间记录。 */
@SpringBootApplication
public class StudyRoomApplication {
  /** 启动HTTP服务；数据库配置错误时由Spring启动失败明确报告。 */
  public static void main(String[] args) {
    SpringApplication.run(StudyRoomApplication.class, args);
  }
}
