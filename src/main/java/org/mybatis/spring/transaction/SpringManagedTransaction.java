/*
 * Copyright 2010-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.mybatis.spring.transaction;

import static org.springframework.util.Assert.notNull;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.apache.ibatis.transaction.Transaction;
import org.mybatis.logging.Logger;
import org.mybatis.logging.LoggerFactory;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 用于：mybatis整合spring，spring管理事务时，获取spring打开的连接
 *
 * 具体实现：去本地线程变量中获取spring早已放好的连接持有器，然后从连接持有器当中获取连接(因为spring会把自己获取好的连接放入连接持有器，然后把连接持有器放入本地线程变量)!
 *
 * 例如：一个方法上标注了@Transaction、那么首先会执行Spring的TransactionInterceptor，在里面，Spring就会获取一个连接，放入连接持有器，然后再把连接持有器放入本地线程变量中；
 * 如果mybatis也想获取到spring获取的连接的话，就同样去本地线程变量中获取即可！这样mybatis和spring使用的就是同一个连接了！
 *
 * {@code SpringManagedTransaction} handles the lifecycle of a JDBC connection. It retrieves a connection from Spring's
 * transaction manager and returns it back to it when it is no longer needed.
 * <p>
 * If Spring's transaction handling is active it will no-op all commit/rollback/close calls assuming that the Spring
 * transaction manager will do the job.
 * <p>
 * If it is not it will behave like {@code JdbcTransaction}.
 *
 * @author Hunter Presnall
 * @author Eduardo Macarron
 */
public class SpringManagedTransaction/* spring管理事务 */ implements Transaction {

  private static final Logger LOGGER = LoggerFactory.getLogger(SpringManagedTransaction.class);

  private final DataSource dataSource;

  // jdbc connection
  private Connection connection;

  private boolean isConnectionTransactional;

  private boolean autoCommit;

  public SpringManagedTransaction(DataSource dataSource) {
    notNull(dataSource, "No DataSource specified");
    this.dataSource = dataSource;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Connection getConnection() throws SQLException {
    if (this.connection == null) {
      // ⚠️会去本地线程变量中获取Spring早已放好的连接持有器，然后从连接持有器当中获取连接！
      openConnection();
    }
    return this.connection;
  }

  /**
   * Gets a connection from Spring transaction manager and discovers if this {@code Transaction} should manage
   * connection or let it to Spring.
   * <p>
   * It also reads autocommit setting because when using Spring Transaction MyBatis thinks that autocommit is always
   * false and will always call commit/rollback so we need to no-op that calls.
   */
  private void openConnection() throws SQLException {
    // ⚠️会去本地线程变量中获取Spring早已放好的连接持有器，然后从连接持有器当中获取连接！
    this.connection = DataSourceUtils.getConnection(this.dataSource);
    this.autoCommit = this.connection.getAutoCommit();
    this.isConnectionTransactional = DataSourceUtils.isConnectionTransactional(this.connection, this.dataSource);

    LOGGER.debug(() -> "JDBC Connection [" + this.connection + "] will"
        + (this.isConnectionTransactional ? " " : " not ") + "be managed by Spring");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void commit() throws SQLException {
    /**
     * Spring commit时，会调用{@link org.mybatis.spring.SqlSessionUtils.SqlSessionSynchronization#beforeCommit(boolean)}，
     * 在SqlSessionSynchronization#beforeCommit(boolean)里面会调用到当前方法。
     *
     * 由于this.isConnectionTransactional=false，所以不会实际执行this.connection.commit()！实际提交的地方是spring控制的，由spring来进行实际的提交！
     *
     * 当然spring提交的时候也不是调用当前方法，而是获取本地线程中的CollectionHolder，然后从CollectionHolder中获取Collection，进行提交！
     */
    if (this.connection != null && !this.isConnectionTransactional && !this.autoCommit) {
      LOGGER.debug(() -> "Committing JDBC Connection [" + this.connection + "]");
      this.connection.commit();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void rollback() throws SQLException {
    if (this.connection != null && !this.isConnectionTransactional && !this.autoCommit) {
      LOGGER.debug(() -> "Rolling back JDBC Connection [" + this.connection + "]");
      this.connection.rollback();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void close() throws SQLException {
    // 释放连接：将ConnectionHolder中的currentConnection置为null
    DataSourceUtils.releaseConnection(this.connection, this.dataSource);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Integer getTimeout() throws SQLException {
    ConnectionHolder holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource);
    if (holder != null && holder.hasTimeout()) {
      return holder.getTimeToLiveInSeconds();
    }
    return null;
  }

}
