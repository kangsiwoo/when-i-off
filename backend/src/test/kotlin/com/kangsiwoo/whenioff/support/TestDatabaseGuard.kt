package com.kangsiwoo.whenioff.support

import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import javax.sql.DataSource

/**
 * 테스트가 개발 DB를 지우지 못하게 막는 가드.
 *
 * 통합 테스트는 `@BeforeEach`에서 `TRUNCATE ... CASCADE`를 실행하고, 컨텍스트가 뜰 때 Flyway가
 * 마이그레이션을 적용한다. 그래서 DataSource가 만들어지는 즉시(Flyway보다 먼저) 실제로 붙은 DB의
 * `current_database()`를 확인하고, 이름이 `_test`로 끝나지 않으면 컨텍스트 기동을 실패시킨다.
 *
 * test 리소스의 `META-INF/spring.factories`로 테스트 클래스패스의 모든 SpringApplication에 붙는다.
 */
class TestDatabaseGuard : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(applicationContext: ConfigurableApplicationContext) {
        applicationContext.beanFactory.addBeanPostProcessor(DataSourceCheck)
    }

    object DataSourceCheck : BeanPostProcessor {
        override fun postProcessAfterInitialization(
            bean: Any,
            beanName: String,
        ): Any {
            if (bean is DataSource) requireTestDatabase(currentDatabase(bean))
            return bean
        }
    }

    companion object {
        const val SUFFIX = "_test"

        fun currentDatabase(dataSource: DataSource): String =
            dataSource.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT current_database()").use { rs ->
                        check(rs.next()) { "SELECT current_database() returned no row" }
                        rs.getString(1)
                    }
                }
            }

        fun requireTestDatabase(name: String?) {
            check(name != null && name.endsWith(SUFFIX)) {
                "테스트가 '$name' DB에 붙었다. 테스트는 TRUNCATE로 데이터를 지우므로 이름이 '$SUFFIX'로 끝나는 " +
                    "DB에서만 돈다. WIO_TEST_DB_URL(기본 jdbc:postgresql://localhost:5432/when_i_off_test)을 확인할 것."
            }
        }
    }
}
