/**
 * Copyright 2009-2019 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.parsing;

/**
 * @author Clinton Begin
 * 通用token解析器
 */
public class GenericTokenParser {


    /**
     * token前缀
     */
    private final String openToken;
    /**
     * token后缀
     */
    private final String closeToken;
    private final TokenHandler handler;

    public GenericTokenParser(String openToken, String closeToken, TokenHandler handler) {
        this.openToken = openToken;
        this.closeToken = closeToken;
        this.handler = handler;
    }

    /**
     * 文本解析
     */
    public String parse(String text) {
        // 空文本直接返回
        if (text == null || text.isEmpty()) {
            return "";
        }
        // 查询token前缀
        int start = text.indexOf(openToken);
        // 不存在直接返回
        if (start == -1) {
            return text;
        }
        // 转换为char 数组便于解析
        char[] src = text.toCharArray();
        // 起始位置
        int offset = 0;
        // 结果
        final StringBuilder builder = new StringBuilder();
        // 匹配 openToken 和 closeToken 之间的表达式
        StringBuilder expression = null;
        // 只要 openToken 还在,循环匹配
        while (start > -1) {
            // openToken 带有转义符
            if (start > 0 && src[start - 1] == '\\') {
                builder.append(src, offset, start - offset - 1).append(openToken);
                offset = start + openToken.length();
            } else {
                // openToken 不带转义符
                // 表达式容器初始化
                if (expression == null) {
                    // null新建一个
                    expression = new StringBuilder();
                } else {
                    // not null 容器实际大小变为 0
                    expression.setLength(0);
                }
                // openToken 之前的数据放入结果集
                builder.append(src, offset, start - offset);
                // 游标指定为openToken之后
                offset = start + openToken.length();
                // openToken 位置之后的 第一个 closeToken 位置
                int end = text.indexOf(closeToken, offset);
                // 存在则循环匹配
                while (end > -1) {
                    // 存在转义符
                    if (end > offset && src[end - 1] == '\\') {
                        // this close token is escaped. remove the backslash and continue.
                        expression.append(src, offset, end - offset - 1).append(closeToken);
                        offset = end + closeToken.length();
                        end = text.indexOf(closeToken, offset);
                    } else {
                        // 不存在转义符, 根据 两个游标获取 ${}中间的 信息
                        expression.append(src, offset, end - offset);
                        // 游标定位到 closeToken后
                        offset = end + closeToken.length();
                        // 退出end循环
                        break;
                    }
                }
                if (end == -1) {
                    // 没有closeToken游标,直接加入结果集
                    builder.append(src, start, src.length - start);
                    offset = src.length;
                } else {
                    // 获取变量值
                    builder.append(handler.handleToken(expression.toString()));
                    // 游标定位到closeToken之后
                    offset = end + closeToken.length();
                }
            }
            // 其实位置定位到下一个openToken
            start = text.indexOf(openToken, offset);
        }
        // 拼接剩余部分
        if (offset < src.length) {
            builder.append(src, offset, src.length - offset);
        }
        return builder.toString();
    }

}
