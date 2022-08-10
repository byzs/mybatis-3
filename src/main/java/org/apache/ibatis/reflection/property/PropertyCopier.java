/**
 *    Copyright 2009-2018 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.reflection.property;

import java.lang.reflect.Field;

import org.apache.ibatis.reflection.Reflector;

/**
 * @author Clinton Begin
 * 属性复制器
 */
public final class PropertyCopier {

  // 防止实例化
  private PropertyCopier() {
    // Prevent Instantiation of Static Class
  }

  /**
   * 将 sourceBean 的属性，复制到 destinationBean 中
   *
   * @param type 指定类
   * @param sourceBean 来源 Bean 对象
   * @param destinationBean 目标 Bean 对象
   */
  public static void copyBeanProperties(Class<?> type, Object sourceBean, Object destinationBean) {
    Class<?> parent = type;
    // 直到没有分类为止
    while (parent != null) {
      // 获取当前类的所有属性
      final Field[] fields = parent.getDeclaredFields();
      // 遍历当前类所有属性
      for(Field field : fields) {
        try {
          try {
            // 从 sourceBean 中，复制到 destinationBean 去
            field.set(destinationBean, field.get(sourceBean));
          } catch (IllegalAccessException e) {
            // 发生权限异常,修改访问权限在进行赋值
            if (Reflector.canControlMemberAccessible()) {
              field.setAccessible(true);
              field.set(destinationBean, field.get(sourceBean));
            } else {
              throw e;
            }
          }
        } catch (Exception e) {
          // 表名异常只会是在获取 final 字段时失败,此处忽略这种异常
          // Nothing useful to do, will only fail on final fields, which will be ignored.
        }
      }
      // 获取父类
      parent = parent.getSuperclass();
    }
  }

}
