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
package org.mybatis.spring;

import static org.springframework.util.Assert.notNull;

import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.logging.Logger;
import org.mybatis.logging.LoggerFactory;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.dao.support.PersistenceExceptionTranslator;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Handles MyBatis SqlSession life cycle. It can register and get SqlSessions from Spring
 * {@code TransactionSynchronizationManager}. Also works if no transaction is active.
 *
 * @author Hunter Presnall
 * @author Eduardo Macarron
 */
public final class SqlSessionUtils {

  private static final Logger LOGGER = LoggerFactory.getLogger(SqlSessionUtils.class);

  private static final String NO_EXECUTOR_TYPE_SPECIFIED = "No ExecutorType specified";
  private static final String NO_SQL_SESSION_FACTORY_SPECIFIED = "No SqlSessionFactory specified";
  private static final String NO_SQL_SESSION_SPECIFIED = "No SqlSession specified";

  /**
   * This class can't be instantiated, exposes static utility methods only.
   */
  private SqlSessionUtils() {
    // do nothing
  }

  /**
   * Creates a new MyBatis {@code SqlSession} from the {@code SqlSessionFactory} provided as a parameter and using its
   * {@code DataSource} and {@code ExecutorType}
   *
   * @param sessionFactory
   *          a MyBatis {@code SqlSessionFactory} to create new sessions
   * @return a MyBatis {@code SqlSession}
   * @throws TransientDataAccessResourceException
   *           if a transaction is active and the {@code SqlSessionFactory} is not using a
   *           {@code SpringManagedTransactionFactory}
   */
  public static SqlSession getSqlSession(SqlSessionFactory sessionFactory) {
    ExecutorType executorType = sessionFactory.getConfiguration().getDefaultExecutorType();
    return getSqlSession(sessionFactory, executorType, null);
  }

  /**
   * Gets an SqlSession from Spring Transaction Manager or creates a new one if needed. Tries to get a SqlSession out of
   * current transaction. If there is not any, it creates a new one. Then, it synchronizes the SqlSession with the
   * transaction if Spring TX is active and <code>SpringManagedTransactionFactory</code> is configured as a transaction
   * manager.
   *
   * @param sessionFactory
   *          a MyBatis {@code SqlSessionFactory} to create new sessions
   * @param executorType
   *          The executor type of the SqlSession to create
   * @param exceptionTranslator
   *          Optional. Translates SqlSession.commit() exceptions to Spring exceptions.
   * @return an SqlSession managed by Spring Transaction Manager
   * @throws TransientDataAccessResourceException
   *           if a transaction is active and the {@code SqlSessionFactory} is not using a
   *           {@code SpringManagedTransactionFactory}
   * @see SpringManagedTransactionFactory
   */
  public static SqlSession getSqlSession(SqlSessionFactory sessionFactory, ExecutorType executorType,
      PersistenceExceptionTranslator exceptionTranslator/* 异常翻译器 */) {

    System.out.println("========测试验证该方法被ide提前调用，导致本地线程变量早已存在SqlSessionHolder========");

    notNull(sessionFactory, NO_SQL_SESSION_FACTORY_SPECIFIED);
    notNull(executorType, NO_EXECUTOR_TYPE_SPECIFIED);

    /* 1、先从本地线程变量中获取sqlSession(题外：在一个spring事务内就有可能从本地线程变量中获取得到sqlSession) */
    /**
     * 1、第一次进来，SqlSessionHolder为null，在后面，会创建一个SqlSessionHolder，放入本地线程变量中，方便下次获取
     *
     * 2、靠的就是本地线程变量存入SqlSessionHolder，实现多个dao方法共用一个sqlSession！
     *
     * 从本地线程变量获取SqlSessionHolder，然后从SqlSessionHolder中，可以获取当前线程中已经存在的sqlSession，保证线程中的多个dao操作，用的都是同一个sqlSession
     */
    // 从本地线程变量中获取SqlSessionFactory对应的SqlSessionHolder
    // 题外：第一次，返回为null
    SqlSessionHolder holder = (SqlSessionHolder) TransactionSynchronizationManager.getResource(sessionFactory);

    // 从SqlSessionHolder中获取sqlSession
    // 题外：第一次，返回为null
    SqlSession session = sessionHolder(executorType, holder);
    if (session != null) {
      return session;
    }

    /*

    2、如果本地线程中没有获取到sqlSession，那么就创建一个新的sqlSession(DefaultSqlSession)
    保证了每个人执行dao方法，都是一个新的sqlSession，避免了其它人产生干扰。比如十个人使用同一UserDao.getUser()，它们如果使用的是同一个代理对象，那么就会产生并发冲突，数据错乱。
    但是内部这里通过sqlSessionProxy的方式，以线程为隔离，为每一个人创建新的sqlSession，避免了冲突。

    */

    LOGGER.debug(() -> "Creating a new SqlSession");
    // ⚠️创建sqlSession对象（DefaultSqlSession）
    session = sessionFactory.openSession(executorType);

    /* 3、如果使用了spring事务，那么就会注册sqlSession到本地线程变量中，方便同一个线程同一个事务内的其它dao方法获取到同一个sqlSession进行公用，保障了事务 */
    // 题外：第一次，不会往里面注册
    registerSessionHolder(sessionFactory, executorType, exceptionTranslator, session);

    return session;
  }

  /**
   * Register session holder if synchronization is active (i.e. a Spring TX is active).
   *
   * Note: The DataSource used by the Environment should be synchronized with the transaction either through
   * DataSourceTxMgr or another tx synchronization. Further assume that if an exception is thrown, whatever started the
   * transaction will handle closing / rolling back the Connection associated with the SqlSession.
   *
   * @param sessionFactory
   *          sqlSessionFactory used for registration.
   * @param executorType
   *          executorType used for registration.
   * @param exceptionTranslator
   *          persistenceExceptionTranslator used for registration.
   * @param session
   *          sqlSession used for registration.
   */
  private static void registerSessionHolder(SqlSessionFactory sessionFactory, ExecutorType executorType,
      PersistenceExceptionTranslator exceptionTranslator, SqlSession session) {
    SqlSessionHolder holder;

    // 只要spring中创建了创建新事务并开启新事务，那么都会初始化synchronizations，所以synchronizations不为空，所以条件成立
    if (TransactionSynchronizationManager.isSynchronizationActive()/* 是同步活动 */) {
      // 获取mybatis中的Environment对象
      Environment environment = sessionFactory.getConfiguration().getEnvironment();
      // Environment对象中的事务工厂是SpringManagedTransactionFactory，
      // 整合了Spring后，事务工厂默认是SpringManagedTransactionFactory，参考：SqlSessionFactoryBean#afterPropertirs()
      if (environment.getTransactionFactory() instanceof SpringManagedTransactionFactory) {
        LOGGER.debug(() -> "Registering transaction synchronization for SqlSession [" + session + "]");

        // 创建一个SqlSessionHolder，包含了刚刚创建的SqlSession、executorType
        holder = new SqlSessionHolder(session, executorType, exceptionTranslator);

        // ⚠️将当前的sqlSessionHolder绑定到本地线程变量中，方便同一个线程同一个事务中，后面的方法从线程变量中获取，然后使用同一个sqlSession
        TransactionSynchronizationManager.bindResource(sessionFactory, holder);

        /**
         * 1、⚠️SqlSessionSynchronization：️️这个用于在spring rollback/commit时，统一关闭sqlSession！
         *
         * 注意：在commit时，虽然会执行SqlSessionSynchronization#beforeCommit()，但是并不会执行connection.commit()，也就是不会实际的提交，
         * 实际提交的地方是spring控制的，由spring来进行实际的提交！
         *
         */
        // 注册一个TransactionSynchronization
        TransactionSynchronizationManager
            .registerSynchronization(new SqlSessionSynchronization(holder, sessionFactory));

        holder.setSynchronizedWithTransaction(true);
        holder.requested();
      } else {
        if (TransactionSynchronizationManager.getResource(environment.getDataSource()) == null) {
          LOGGER.debug(() -> "SqlSession [" + session
              + "] was not registered for synchronization because DataSource is not transactional");
        } else {
          throw new TransientDataAccessResourceException(
              "SqlSessionFactory must be using a SpringManagedTransactionFactory in order to use Spring transaction synchronization");
        }
      }
    }

    // 第一次，走这里
    else {
      LOGGER.debug(() -> "SqlSession [" + session
          + "] was not registered for synchronization because synchronization is not active");
    }

  }

  private static SqlSession sessionHolder(ExecutorType executorType, SqlSessionHolder holder) {
    SqlSession session = null;

    if (holder != null && holder.isSynchronizedWithTransaction()) {

      // SqlSessionHolder中的执行器类型，与当前dao执行方法所需要的执行器类型不相同，则报错
      if (holder.getExecutorType() != executorType) {
        throw new TransientDataAccessResourceException(
            // 存在事务时无法更改ExecutorType
            "Cannot change the ExecutorType when there is an existing transaction");
      }

      // 增加使用SqlSessionHolder中SqlSession的方法数量
      holder.requested();

      LOGGER.debug(() -> "Fetched SqlSession [" + holder.getSqlSession() + "] from current transaction");

      // 获取SqlSessionHolder中的SqlSession
      session = holder.getSqlSession();
    }

    return session;
  }

  /**
   * Checks if {@code SqlSession} passed as an argument is managed by Spring {@code TransactionSynchronizationManager}
   * If it is not, it closes it, otherwise it just updates the reference counter and lets Spring call the close callback
   * when the managed transaction ends
   *
   * @param session
   *          a target SqlSession
   * @param sessionFactory
   *          a factory of SqlSession
   */
  public static void closeSqlSession(SqlSession session, SqlSessionFactory sessionFactory) {
    notNull(session, NO_SQL_SESSION_SPECIFIED);
    notNull(sessionFactory, NO_SQL_SESSION_FACTORY_SPECIFIED);

    /* 1、判断是不是在一个spring事务当中，是的话，就不自行关闭sqlSession(因为关闭sqlSession意味着结束事务)，只是减少SqlSessionHolder的引用计数，让spring控制关闭sqlSession */

    SqlSessionHolder holder = (SqlSessionHolder) TransactionSynchronizationManager.getResource(sessionFactory);
    // 本地线程变量中存在SqlSessionHolder && 当前sqlSession与SqlSessionHolder中的sqlSession一致
    // 也就是说当前是在一个spring事务当中，就不关闭sqlSession，让spring控制关闭sqlSession，只是减少SqlSessionHolder的引用计数
    if ((holder != null) && (holder.getSqlSession() == session)) {
      LOGGER.debug(() -> "Releasing transactional SqlSession [" + session + "]");
      holder.released();
    }
    /* 2、不是的话，就关闭sqlSession */
    else {
      LOGGER.debug(() -> "Closing non transactional SqlSession [" + session + "]");
      // 关闭sqlSession
      session.close();
    }
  }

  /**
   * Returns if the {@code SqlSession} passed as an argument is being managed by Spring
   *
   * @param session
   *          a MyBatis SqlSession to check
   * @param sessionFactory
   *          the SqlSessionFactory which the SqlSession was built with
   * @return true if session is transactional, otherwise false
   */
  public static boolean isSqlSessionTransactional(SqlSession session, SqlSessionFactory sessionFactory) {
    notNull(session, NO_SQL_SESSION_SPECIFIED);
    notNull(sessionFactory, NO_SQL_SESSION_FACTORY_SPECIFIED);

    SqlSessionHolder holder = (SqlSessionHolder) TransactionSynchronizationManager.getResource(sessionFactory);

    // 本地线程变量中存在SqlSessionHolder && 当前sqlSession与SqlSessionHolder中的sqlSession一致，
    // 也就是说当前是在一个spring事务当中，就不自行提交，而是由spring来控制提交
    return (holder != null) && (holder.getSqlSession() == session);
  }

  /**
   * Callback for cleaning up resources. It cleans TransactionSynchronizationManager and also commits and closes the
   * {@code SqlSession}. It assumes that {@code Connection} life cycle will be managed by
   * {@code DataSourceTransactionManager} or {@code JtaTransactionManager}
   */
  private static final class SqlSessionSynchronization extends TransactionSynchronizationAdapter {

    // 里面具备sqlSession
    private final SqlSessionHolder holder;

    private final SqlSessionFactory sessionFactory;

    private boolean holderActive = true;

    public SqlSessionSynchronization(SqlSessionHolder holder, SqlSessionFactory sessionFactory) {
      notNull(holder, "Parameter 'holder' must be not null");
      notNull(sessionFactory, "Parameter 'sessionFactory' must be not null");

      this.holder = holder;
      this.sessionFactory = sessionFactory;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getOrder() {
      // order right before any Connection synchronization
      return DataSourceUtils.CONNECTION_SYNCHRONIZATION_ORDER - 1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void suspend() {
      if (this.holderActive) {
        LOGGER.debug(() -> "Transaction synchronization suspending SqlSession [" + this.holder.getSqlSession() + "]");
        TransactionSynchronizationManager.unbindResource(this.sessionFactory);
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void resume() {
      if (this.holderActive) {
        LOGGER.debug(() -> "Transaction synchronization resuming SqlSession [" + this.holder.getSqlSession() + "]");
        TransactionSynchronizationManager.bindResource(this.sessionFactory, this.holder);
      }
    }

    /**
     * spring commit时会调用当前方法(rollback不会)
     *
     * {@inheritDoc}
     */
    @Override
    public void beforeCommit(boolean readOnly) {
      // Connection commit or rollback will be handled by ConnectionSynchronization or
      // DataSourceTransactionManager. —— 连接提交或回滚将由 ConnectionSynchronization 或 DataSourceTransactionManager 处理。
      // But, do cleanup the SqlSession / Executor, including flushing BATCH statements so
      // they are actually executed. —— 但是，请清理 SqlSession Executor，包括刷新 BATCH 语句，以便它们实际执行。
      // SpringManagedTransaction will no-op the commit over the jdbc connection —— SpringManagedTransaction 将通过 jdbc 连接不操作提交
      // TODO This updates 2nd level caches but the tx may be rolledback later on! —— TODO 这会更新二级缓存，但稍后可能会回滚 tx！
      if (TransactionSynchronizationManager.isActualTransactionActive()) {
        try {
          LOGGER.debug(() -> "Transaction synchronization committing SqlSession [" + this.holder.getSqlSession() + "]");
          // 提交事务
          // 注意：在spring commit时，虽然会执行当前方法，但是内部并不会执行connection.commit()，也就是不会实际的提交，实际提交的地方是spring控制的，由spring来进行实际的提交！
          // >>> 当然spring提交的时候也不是调用SqlSession().commit()，也不是调用Transaction.commit()，而是获取本地线程中的CollectionHolder，然后从CollectionHolder中获取Collection，进行提交！
          this.holder.getSqlSession().commit();
        } catch (PersistenceException p) {
          if (this.holder.getPersistenceExceptionTranslator() != null) {
            DataAccessException translated = this.holder.getPersistenceExceptionTranslator()
                .translateExceptionIfPossible(p);
            if (translated != null) {
              throw translated;
            }
          }
          throw p;
        }
      }
    }

    /**
     * spring rollback/commit时会调用当前方法
     *
     * {@inheritDoc}
     */
    @Override
    public void beforeCompletion() {
      // Issue #18 Close SqlSession and deregister it now
      // because afterCompletion may be called from a different thread
      if (!this.holder.isOpen()) {
        LOGGER
            .debug(() -> "Transaction synchronization deregistering SqlSession [" + this.holder.getSqlSession() + "]");
        TransactionSynchronizationManager.unbindResource(sessionFactory);
        this.holderActive = false;
        LOGGER.debug(() -> "Transaction synchronization closing SqlSession [" + this.holder.getSqlSession() + "]");
        // ⚠️关闭sqlSession
        this.holder.getSqlSession().close();
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void afterCompletion(int status) {
      if (this.holderActive) {
        // afterCompletion may have been called from a different thread
        // so avoid failing if there is nothing in this one
        LOGGER
            .debug(() -> "Transaction synchronization deregistering SqlSession [" + this.holder.getSqlSession() + "]");
        TransactionSynchronizationManager.unbindResourceIfPossible(sessionFactory);
        this.holderActive = false;
        LOGGER.debug(() -> "Transaction synchronization closing SqlSession [" + this.holder.getSqlSession() + "]");

        this.holder.getSqlSession().close();
      }
      this.holder.reset();
    }
  }

}
