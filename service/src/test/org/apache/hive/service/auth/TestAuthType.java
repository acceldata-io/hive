/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hive.service.auth;

import com.google.common.collect.ImmutableSet;

import org.apache.commons.lang3.EnumUtils;
import org.apache.hadoop.hive.conf.HiveServer2TransportMode;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tests to test if AuthType can parse allowed values and reject disallowed combinations.
 */
public class TestAuthType {
  @Test
  public void testSingleAuth() throws Exception {
    for (HiveAuthConstants.AuthTypes type : HiveAuthConstants.AuthTypes.values()) {
      testSingleAuth(type);
    }
  }

  private void testSingleAuth(HiveAuthConstants.AuthTypes type) throws Exception {
    AuthType authType = new AuthType(type.getAuthName(), HiveServer2TransportMode.http);
    Assert.assertTrue(authType.isEnabled(type));
    if (type == HiveAuthConstants.AuthTypes.NOSASL || type == HiveAuthConstants.AuthTypes.NONE ||
        AuthType.PASSWORD_BASED_TYPES.contains(type)) {
      Assert.assertEquals(type.getAuthName(), authType.getPasswordBasedAuthStr());
    } else {
      Assert.assertEquals("Should return empty string if no password based authentication is set.",
          "", authType.getPasswordBasedAuthStr());
    }
  }

  @Test
  public void testOnePasswordAuthWithSAML() throws Exception {
    testOnePasswordAuthWithSAML(HiveAuthConstants.AuthTypes.LDAP);
    testOnePasswordAuthWithSAML(HiveAuthConstants.AuthTypes.PAM);
    testOnePasswordAuthWithSAML(HiveAuthConstants.AuthTypes.CUSTOM);
  }

  private void testOnePasswordAuthWithSAML(HiveAuthConstants.AuthTypes type) throws Exception {
    AuthType authType = new AuthType("SAML," + type.getAuthName(), HiveServer2TransportMode.http);
    Assert.assertTrue(authType.isEnabled(HiveAuthConstants.AuthTypes.SAML));
    Assert.assertTrue(authType.isEnabled(type));

    Set<HiveAuthConstants.AuthTypes> disabledAuthTypes = Arrays.stream(HiveAuthConstants.AuthTypes.values())
        .collect(Collectors.toSet());
    disabledAuthTypes.remove(HiveAuthConstants.AuthTypes.SAML);
    disabledAuthTypes.remove(type);
    for (HiveAuthConstants.AuthTypes disabledType : disabledAuthTypes) {
      Assert.assertFalse(authType.isEnabled(disabledType));
    }
    Assert.assertEquals(type.getAuthName(), authType.getPasswordBasedAuthStr());

    verify("SAML," + type.getAuthName(), HiveServer2TransportMode.binary, true);
    verify("SAML," + type.getAuthName(), HiveServer2TransportMode.all, true);
  }

  private void verify(String authTypes, HiveServer2TransportMode mode, boolean shouldThrowException) {
    try {
      AuthType authType = new AuthType(authTypes, mode);
      if (shouldThrowException) {
        Assert.fail("HiveServer2 " + mode.name() + " mode cann't support " + authTypes + " by design");
      } else {
        String[] authMethods = authTypes.split(",");
        for (int i = 0; i < authMethods.length; i++) {
          HiveAuthConstants.AuthTypes authMech = EnumUtils.getEnumIgnoreCase(HiveAuthConstants.AuthTypes.class,
              authMethods[i]);
          Assert.assertTrue(authType.isEnabled(authMech));
          if (i == 0) {
            Assert.assertTrue(authType.isLoadedFirst(authMech));
          } else {
            Assert.assertFalse(authType.isLoadedFirst(authMech));
          }
        }
      }
    } catch (Exception e) {
      if (!shouldThrowException) {
        Assert.fail("HiveServer2 " + mode.name() + " mode should be able to support " + authTypes);
      } else {
        Assert.assertTrue(e instanceof RuntimeException);
      }
    }
  }

  @Test(expected = Exception.class)
  public void testKerberosWithSAML() throws Exception {
    AuthType authType = new AuthType("KERBEROS,SAML");
  }

  @Test(expected = Exception.class)
  public void testKerberosWithSAMLAndLdap() throws Exception {
    AuthType authType = new AuthType("KERBEROS,SAML,LDAP");
  }

  @Test(expected = Exception.class)
  public void testKerberosWithLdap() throws Exception {
    AuthType authType = new AuthType("KERBEROS,LDAP");
  }

  @Test(expected = Exception.class)
  public void testNoneWithSAML() throws Exception {
    AuthType authType = new AuthType("NONE,SAML");
  }

  @Test(expected = Exception.class)
  public void testNoSaslWithSAML() throws Exception {
    AuthType authType = new AuthType("NOSASL,SAML");
  }

  @Test(expected = Exception.class)
  public void testMultiPasswordAuthWithSAML() throws Exception {
    AuthType authType = new AuthType("SAML,LDAP,PAM,CUSTOM");
  }

  @Test(expected = Exception.class)
  public void testMultiPasswordAuth() throws Exception {
    AuthType authType = new AuthType("LDAP,PAM,CUSTOM");
  }

  @Test(expected = Exception.class)
  public void testNotExistAuth() throws Exception {
    AuthType authType = new AuthType("SAML,OTHER");
  }
}
