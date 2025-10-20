package org.apache.ibatis.byz;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.Test;
import org.junit.jupiter.api.BeforeAll;

import java.io.IOException;
import java.io.Reader;

/**
 * @Description TODO
 * @Date 2025/9/8 19:57
 * @Auth byz
 */
public class ByzTest {

  private static SqlSessionFactory sqlSessionFactory;

//  @BeforeAll
//  static void setUp() throws Exception {
//    try (Reader reader = Resources.getResourceAsReader("org/apache/ibatis/byz/mybatis-config.xml")) {
//      sqlSessionFactory = new SqlSessionFactoryBuilder().build(reader);
//    }
//  }

  /**
   * 根据mybatis配置文件初始化SqlSession对象
   */
  @Test
  public void sqlSessionBuild() throws IOException {
    try (Reader reader = Resources.getResourceAsReader("org/apache/ibatis/byz/mybatis-config.xml")) {
      sqlSessionFactory = new SqlSessionFactoryBuilder().build(reader);
    }
  }


}
