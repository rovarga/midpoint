/*
 * Copyright (c) 2010-2019 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.testing.schrodinger.labs;

import com.codeborne.selenide.Selenide;

import com.codeborne.selenide.ex.ElementNotFound;

import com.evolveum.midpoint.schrodinger.MidPoint;
import com.evolveum.midpoint.schrodinger.component.AssignmentHolderBasicTab;
import com.evolveum.midpoint.schrodinger.component.AssignmentsTab;
import com.evolveum.midpoint.schrodinger.component.common.PrismForm;
import com.evolveum.midpoint.schrodinger.component.common.PrismFormWithActionButtons;
import com.evolveum.midpoint.schrodinger.component.configuration.ObjectPolicyTab;
import com.evolveum.midpoint.schrodinger.component.org.OrgRootTab;
import com.evolveum.midpoint.schrodinger.component.resource.ResourceAccountsTab;
import com.evolveum.midpoint.schrodinger.page.resource.ViewResourcePage;
import com.evolveum.midpoint.schrodinger.page.task.TaskPage;
import com.evolveum.midpoint.schrodinger.page.user.UserPage;
import com.evolveum.midpoint.testing.schrodinger.scenarios.ScenariosCommons;

import org.apache.commons.io.FileUtils;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;

/**
 * @author skublik
 */

public class M10ObjectTemplate extends AbstractLabTest{

    private static final File OBJECT_TEMPLATE_USER_SIMPLE_FILE = new File(LAB_OBJECTS_DIRECTORY + "objectTemplate/object-template-example-user-simple.xml");
    private static final File OBJECT_TEMPLATE_USER_FILE = new File(LAB_OBJECTS_DIRECTORY + "objectTemplate/object-template-example-user.xml");
    private static final File OBJECT_TEMPLATE_USER_FILE_10_3 = new File(LAB_OBJECTS_DIRECTORY + "objectTemplate/object-template-example-user-10-3.xml");
    private static final File LOOKUP_EMP_STATUS_FILE = new File(LAB_OBJECTS_DIRECTORY + "lookupTables/lookup-emp-status.xml");
    private static final File CSV_3_RESOURCE_FILE_10_4 = new File(LAB_OBJECTS_DIRECTORY + "resources/localhost-csvfile-3-ldap-10-4.xml");

    @Test(groups={"M10"}, dependsOnGroups={"M9"})
    public void mod10test01SimpleObjectTemplate() throws IOException {
        importObject(OBJECT_TEMPLATE_USER_SIMPLE_FILE, true);

        ((PrismFormWithActionButtons<ObjectPolicyTab>)basicPage.objectPolicy()
                .clickAddObjectPolicy()
                    .selectOption("type", "User")
                    .editRefValue("objectTemplateRef")
                        .table()
                            .clickByName("ExAmPLE User Template"))
                    .clickDone()
                    .and()
                .save()
                    .feedback()
                        .isSuccess();

        showUser("X001212")
                .checkReconcile()
                .clickSave()
                    .feedback()
                        .isSuccess();

        Assert.assertTrue(showUser("X001212")
                .selectTabBasic()
                    .form()
                        .compareInputAttributeValue("fullName", "John Smith"));

        showTask("HR Synchronization").clickResume();

        FileUtils.copyFile(HR_SOURCE_FILE_10_1, hrTargetFile);
        Selenide.sleep(MidPoint.TIMEOUT_MEDIUM_6_S);

        Assert.assertTrue(showUser("X000998")
                .selectTabBasic()
                .form()
                .compareInputAttributeValue("fullName", "David Lister"));

        TaskPage task = basicPage.newTask();
        task.setHandlerUriForNewTask("Recompute task");
        task.selectTabBasic()
                .form()
                    .addAttributeValue("name", "User Recomputation Task")
                    .selectOption("recurrence","Single")
                    .selectOption("objectType","User")
                    .and()
                .and()
            .clickSaveAndRun()
                .feedback()
                    .isInfo();

        Assert.assertTrue(showUser("kirk")
                .selectTabBasic()
                .form()
                .compareInputAttributeValue("fullName", "Jim Tiberius Kirk"));
    }

    @Test(dependsOnMethods = {"mod10test01SimpleObjectTemplate"}, groups={"M10"}, dependsOnGroups={"M9"})
    public void mod10test02AutomaticAssignments() throws IOException {
        importObject(OBJECT_TEMPLATE_USER_FILE, true);

        ResourceAccountsTab<ViewResourcePage> accountTab = basicPage.listResources()
                .table()
                    .clickByName(HR_RESOURCE_NAME)
                        .clickAccountsTab()
                            .clickSearchInResource();
        Selenide.sleep(MidPoint.TIMEOUT_DEFAULT_2_S);
        accountTab.table()
                .selectCheckboxByName("001212")
                    .clickHeaderActionDropDown()
                        .clickImport()
                    .and()
                .and()
            .feedback()
                .isSuccess();

        AssignmentsTab<UserPage> tab = accountTab.table()
                .clickOnOwnerByName("X001212")
                .selectTabAssignments();

        Assert.assertTrue(tab.containsAssignmentsWithRelation("Default", "Human Resources",
                "Active Employees", "Internal Employee"));
        Assert.assertTrue(tab.containsAssignmentsWithRelation("Manager", "Human Resources"));

        FileUtils.copyFile(HR_SOURCE_FILE_10_2_PART1, hrTargetFile);
        Selenide.sleep(MidPoint.TIMEOUT_MEDIUM_6_S);

        Assert.assertTrue(showUser("X000999")
            .selectTabAssignments()
                .containsAssignmentsWithRelation("Default", "Java Development",
                "Active Employees", "Internal Employee"));

        showTask("User Recomputation Task").clickRunNow();
        Selenide.sleep(MidPoint.TIMEOUT_MEDIUM_6_S);

        Assert.assertTrue(showUser("X000998")
                .selectTabAssignments()
                .containsAssignmentsWithRelation("Default", "Java Development",
                        "Active Employees", "Internal Employee"));

        FileUtils.copyFile(HR_SOURCE_FILE_10_2_PART2, hrTargetFile);
        Selenide.sleep(MidPoint.TIMEOUT_MEDIUM_6_S);

        UserPage user = showUser("X000998");
        Assert.assertTrue(user.selectTabBasic()
                .form()
                    .compareSelectAttributeValue("administrativeStatus", "Disabled"));
        Assert.assertTrue(user.selectTabAssignments()
                .containsAssignmentsWithRelation("Default", "Inactive Employees", "Internal Employee"));

        FileUtils.copyFile(HR_SOURCE_FILE_10_2_PART3, hrTargetFile);
        Selenide.sleep(MidPoint.TIMEOUT_MEDIUM_6_S);

        user = showUser("X000998");
        Assert.assertTrue(user.selectTabBasic()
                .form()
                .compareSelectAttributeValue("administrativeStatus", "Disabled"));
        Assert.assertTrue(user.selectTabAssignments()
                .containsAssignmentsWithRelation("Default", "Former Employees"));

        FileUtils.copyFile(HR_SOURCE_FILE_10_2_PART1, hrTargetFile);
        Selenide.sleep(MidPoint.TIMEOUT_MEDIUM_6_S);

        user = showUser("X000998");
        Assert.assertTrue(user.selectTabBasic()
                .form()
                .compareSelectAttributeValue("administrativeStatus", "Enabled"));
        Assert.assertTrue(showUser("X000998")
                .selectTabAssignments()
                .containsAssignmentsWithRelation("Default", "Java Development",
                        "Active Employees", "Internal Employee"));
    }

    @Test(dependsOnMethods = {"mod10test02AutomaticAssignments"}, groups={"M10"}, dependsOnGroups={"M9"})
    public void mod10test03LookupTablesAndAttributeOverrides() {

        PrismForm<AssignmentHolderBasicTab<UserPage>> form = showUser("kirk")
                .selectTabBasic()
                    .form();

        form.showEmptyAttributes("Properties");
        form.addAttributeValue("empStatus", "O");
        form.addAttributeValue("familyName", "kirk2");
        boolean existFeedback = false;
        try { existFeedback = form.and().and().feedback().isError(); } catch (ElementNotFound e) { }
        Assert.assertFalse(existFeedback);
        Assert.assertTrue(form.findProperty("telephoneNumber").
                $x(".//i[contains(@data-original-title, 'Primary telephone number of the user, org. unit, etc.')]").exists());
        Assert.assertFalse(form.findProperty("telephoneNumber").
                $x(".//i[contains(@data-original-title, 'Mobile Telephone Number')]").exists());
        Assert.assertTrue(form.isPropertyEnabled("honorificSuffix"));

        importObject(LOOKUP_EMP_STATUS_FILE, true);
        importObject(OBJECT_TEMPLATE_USER_FILE_10_3, true);

        form = showUser("kirk")
                .selectTabBasic()
                .form();

        form.showEmptyAttributes("Properties");
        form.addAttributeValue("empStatus", "O");
        form.addAttributeValue("familyName", "kirk2");
        Assert.assertTrue(form.and().and().feedback().isError());
        Assert.assertFalse(form.findProperty("telephoneNumber").
                $x(".//i[contains(@data-original-title, 'Primary telephone number of the user, org. unit, etc.')]").exists());
        Assert.assertTrue(form.findProperty("telephoneNumber").
                $x(".//i[contains(@data-original-title, 'Mobile Telephone Number')]").exists());
        Assert.assertFalse(form.isPropertyEnabled("honorificSuffix"));
    }

    @Test(dependsOnMethods = {"mod10test03LookupTablesAndAttributeOverrides"}, groups={"M10"}, dependsOnGroups={"M9"})
    public void mod10test04FinishingManagerMapping() {
        Selenide.sleep(MidPoint.TIMEOUT_MEDIUM_6_S);
        showTask("User Recomputation Task").clickRunNow();
        Selenide.sleep(MidPoint.TIMEOUT_MEDIUM_6_S);

        OrgRootTab rootTab = basicPage.orgStructure()
                .selectTabWithRootOrg("ExAmPLE, Inc. - Functional Structure");
        Assert.assertTrue(rootTab.getOrgHierarchyPanel()
                .expandAllOrgs()
                .selectOrgInTree("IT Administration Department")
                .and()
            .getManagerPanel()
                .containsManager("John Wicks"));

        rootTab.getMemberPanel()
                .selectType("User")
                .table()
                    .search()
                        .resetBasicSearch()
                    .and()
                .clickByName("X000158");
        Assert.assertTrue(new UserPage().selectTabProjections()
                .table()
                    .clickByName("cn=Alice Black,ou=0212,ou=0200,ou=ExAmPLE,dc=example,dc=com")
                        .compareInputAttributeValue("manager", "X000390"));
        Assert.assertTrue(showUser("X000390").selectTabProjections()
                .table()
                    .clickByName("cn=John Wicks,ou=0212,ou=0200,ou=ExAmPLE,dc=example,dc=com")
                        .compareInputAttributeValue("manager", "X000035"));
        Assert.assertTrue(showUser("X000035").selectTabProjections()
                .table()
                    .clickByName("cn=James Bradley,ou=0200,ou=ExAmPLE,dc=example,dc=com")
                        .showEmptyAttributes("Attributes")
                        .compareInputAttributeValue("manager", ""));

        Assert.assertTrue(showUser("kirk")
                .selectTabAssignments()
                    .containsAssignmentsWithRelation("Default", "Warp Speed Research"));
        Assert.assertTrue(new UserPage().selectTabProjections()
                .table()
                    .clickByName("cn=Jim Tiberius Kirk,ou=ExAmPLE,dc=example,dc=com")
                        .showEmptyAttributes("Attributes")
                        .compareInputAttributeValue("manager", ""));

        showUser("picard")
                .selectTabAssignments()
                    .clickAddAssignemnt("New Organization type assignment with manager relation")
                        .selectType("Org")
                            .table()
                                .search()
                                    .byName()
                                        .inputValue("0919")
                                        .updateSearch()
                                    .and()
                                .selectCheckboxByName("0919")
                                .and()
                            .clickAdd()
                            .and()
                        .clickSave()
                            .feedback()
                                .isSuccess();

        showUser("kirk").checkReconcile()
                .clickSave()
                    .feedback()
                        .isSuccess();

        Assert.assertTrue(showUser("kirk").selectTabProjections()
                .table()
                    .clickByName("cn=Jim Tiberius Kirk,ou=ExAmPLE,dc=example,dc=com")
                        .compareInputAttributeValue("manager", "picard"));

        showUser("picard").selectTabAssignments()
                .table()
                    .selectCheckboxByName("Warp Speed Research")
                    .removeByName("Warp Speed Research")
                    .and()
                .and()
            .clickSave()
                .feedback()
                    .isSuccess();

        Assert.assertTrue(showUser("kirk").selectTabProjections()
                .table()
                    .clickByName("cn=Jim Tiberius Kirk,ou=ExAmPLE,dc=example,dc=com")
                        .compareInputAttributeValue("manager", "picard"));

        importObject(CSV_3_RESOURCE_FILE_10_4,true);
        changeResourceAttribute(CSV_3_RESOURCE_NAME, ScenariosCommons.CSV_RESOURCE_ATTR_FILE_PATH, csv3TargetFile.getAbsolutePath(), true);

        showUser("kirk").checkReconcile()
                .clickSave()
                    .feedback()
                        .isSuccess();

        Assert.assertTrue(showUser("kirk").selectTabProjections()
                .table()
                    .clickByName("cn=Jim Tiberius Kirk,ou=ExAmPLE,dc=example,dc=com")
                        .showEmptyAttributes("Attributes")
                        .compareInputAttributeValue("manager", ""));
    }

}
