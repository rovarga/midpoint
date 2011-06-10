/*
 * Copyright (c) 2011 Evolveum
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://www.opensource.org/licenses/cddl1 or
 * CDDLv1.0.txt file in the source code distribution.
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 *
 * Portions Copyrighted 2011 [name of copyright owner]
 * Portions Copyrighted 2011 Peter Prochazka
 */

package com.evolveum.midpoint.common.test;

import static org.junit.Assert.*;

import java.io.File;

import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;

import org.junit.Test;

import com.evolveum.midpoint.api.logging.Trace;
import com.evolveum.midpoint.common.jaxb.JAXBUtil;
import com.evolveum.midpoint.common.password.PasswordGenerator;
import com.evolveum.midpoint.common.password.PasswordPolicyUtils;
import com.evolveum.midpoint.common.result.OperationResult;
import com.evolveum.midpoint.common.result.OperationResultStatus;
import com.evolveum.midpoint.common.string.StringPolicyUtils;
import com.evolveum.midpoint.xml.ns._public.common.common_1.PasswordPolicyType;

import com.evolveum.midpoint.util.DOMUtil;
import com.evolveum.midpoint.common.jaxb.JAXBUtil;
import com.evolveum.midpoint.logging.TraceManager;
import com.evolveum.midpoint.xml.ns._public.common.common_1.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.StringLimitType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.StringPolicyType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.UserType;
import javax.xml.bind.JAXBException;
import javax.xml.namespace.QName;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import static org.junit.Assert.*;

public class PasswordPolicyValidatorTest {

	public PasswordPolicyValidatorTest() {

	}

	public static final String BASE_PATH = "src/test/resources/";

	private static final transient Trace logger = TraceManager.getTrace(PasswordPolicyValidatorTest.class);

	@Test
	public void stringPolicyUtilsMinimalTest() {
		String filename = "password-policy-minimal.xml";
		String pathname = BASE_PATH + filename;
		File file = new File(pathname);
		JAXBElement<PasswordPolicyType> jbe = null;
		try {
			jbe = (JAXBElement<PasswordPolicyType>) JAXBUtil.unmarshal(file);
		} catch (Exception e) {
			e.printStackTrace();
		}

		PasswordPolicyType pp = jbe.getValue();
		StringPolicyType sp = pp.getStringPolicy();
		StringPolicyUtils.normalize(sp);
		assertNotNull(sp.getCharacterClass());
		assertNotNull(sp.getLimitations().getLimit());
		assertTrue(-1 == sp.getLimitations().getMaxLength());
		assertTrue(0 == sp.getLimitations().getMinLength());
		assertTrue(0 == " !\"#$%&'()*+,-.01234567890:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~"
				.compareTo(sp.getCharacterClass().getValue()));
	}

	/*******************************************************************************************/
	@Test
	public void stringPolicyUtilsComplexTest() {
		String filename = "password-policy-complex.xml";
		String pathname = BASE_PATH + filename;
		File file = new File(pathname);
		JAXBElement<PasswordPolicyType> jbe = null;
		try {
			jbe = (JAXBElement<PasswordPolicyType>) JAXBUtil.unmarshal(file);
		} catch (Exception e) {
			e.printStackTrace();
		}

		PasswordPolicyType pp = jbe.getValue();
		StringPolicyType sp = pp.getStringPolicy();
		StringPolicyUtils.normalize(sp);
	}

	/*******************************************************************************************/
	@Test
	public void passwordGeneratorComplexTest() {
		String filename = "password-policy-complex.xml";
		String pathname = BASE_PATH + filename;
		File file = new File(pathname);
		JAXBElement<PasswordPolicyType> jbe = null;
		try {
			jbe = (JAXBElement<PasswordPolicyType>) JAXBUtil.unmarshal(file);
		} catch (Exception e) {
			e.printStackTrace();
		}
		logger.error("Positive testing: passwordGeneratorComplexTest");
		PasswordPolicyType pp = jbe.getValue();
		OperationResult op = new OperationResult("passwordGeneratorComplexTest");
		String psswd;
		// generate minimal size passwd
		for (int i = 0; i < 100; i++) {
			psswd = PasswordGenerator.generate(pp, true ,op);
			logger.error("Generated password:" + psswd);
			op.computeStatus();
			if ( ! op.isSuccess()) {
				logger.error("Result:" + op.debugDump());
			}
			assertTrue(op.isSuccess());
			assertNotNull(psswd);
			
		}
		//genereata to meet as possible
		logger.error("-------------------------");
		// Generate up to possible
		for (int i = 0; i < 100; i++) {
			psswd = PasswordGenerator.generate(pp, false ,op);
			logger.error("Generated password:" + psswd);
			op.computeStatus();
			if ( ! op.isSuccess()) {
				logger.error("Result:" + op.debugDump());
			}
			assertTrue(op.isSuccess());
			assertNotNull(psswd);
			
		}
		op = new OperationResult("passwordGeneratorComplexTest");
		// Make switch some cosistency
		pp.getStringPolicy().getLimitations().setMinLength(2);
		pp.getStringPolicy().getLimitations().setMinUniqueChars(5);
		psswd = PasswordGenerator.generate(pp, op);
		op.computeStatus();
		assertNotNull(psswd);
		assertTrue(op.isAcceptable());
		
		// Switch to all must be first :-) to test if there is error
		for (StringLimitType l : pp.getStringPolicy().getLimitations().getLimit()) {
			l.setMustBeFirst(true);
		}
		logger.error("Negative testing: passwordGeneratorComplexTest");
		psswd = PasswordGenerator.generate(pp, op);
		assertNull(psswd);
		op.computeStatus();
		assertTrue(op.getStatus() == OperationResultStatus.FATAL_ERROR);
	}

	/*******************************************************************************************/
	@Test
	public void passwordValidationTest() {
		String filename = "password-policy-complex.xml";
		String pathname = BASE_PATH + filename;
		File file = new File(pathname);
		JAXBElement<PasswordPolicyType> jbe = null;
		try {
			jbe = (JAXBElement<PasswordPolicyType>) JAXBUtil.unmarshal(file);
		} catch (Exception e) {
			e.printStackTrace();
		}
		logger.error("Positive testing: passwordGeneratorComplexTest");
		PasswordPolicyType pp = jbe.getValue();
		
		//Test on all cases
		assertTrue(pwdValidHelper("582a**A", pp));
		assertFalse(pwdValidHelper("58", pp));
		assertFalse(pwdValidHelper("333a**aGaa", pp));
		assertFalse(pwdValidHelper("AAA4444", pp));
	}
	
	private boolean pwdValidHelper(String password, PasswordPolicyType pp) {
		OperationResult op = new OperationResult("Password Validator test with password:" + password);
		PasswordPolicyUtils.validatePassword(password, pp, op);
		op.computeStatus();
		logger.error(op.debugDump());
		return (op.isSuccess());
	}
	
	/*******************************************************************************************/
	@Test
	public void XMLPasswordPolicy() {

		String filename = "password-policy-complex.xml";
		String pathname = BASE_PATH + filename;
		File file = new File(pathname);
		JAXBElement<PasswordPolicyType> jbe = null;
		try {
			jbe = (JAXBElement<PasswordPolicyType>) JAXBUtil.unmarshal(file);
		} catch (Exception e) {
			e.printStackTrace();
		}

		PasswordPolicyType pp = jbe.getValue();

		OperationResult op = new OperationResult("Generator testing");

		// String pswd = PasswordPolicyUtils.generatePassword(pp, op);
		// logger.info("Generated password: " + pswd);
		// assertNotNull(pswd);
		// assertTrue(op.isSuccess());
	}
}