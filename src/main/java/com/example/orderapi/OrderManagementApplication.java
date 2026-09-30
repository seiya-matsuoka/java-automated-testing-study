package com.example.orderapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 注文管理 REST API のエントリーポイント。
 *
 * <p>{@link SpringBootApplication} は、Spring Boot アプリケーションに必要な Configuration、Auto
 * Configuration、Component Scan の起点をまとめて定義する。
 *
 * <p>{@code com.example.orderapi} 配下を Component Scan の対象とし、 Controller、Service、Repository などの
 * Spring Bean を検出する。
 */
@SpringBootApplication
public class OrderManagementApplication {

  /**
   * Spring Boot アプリケーションを起動する。
   *
   * <p>{@link SpringApplication#run(Class, String...)} により Spring Context を構築し、 Web アプリケーションとして必要な
   * Auto Configuration を適用する。 アプリケーション全体の起動処理を担う main method。
   *
   * @param args コマンドライン引数
   */
  public static void main(String[] args) {
    SpringApplication.run(OrderManagementApplication.class, args);
  }
}
