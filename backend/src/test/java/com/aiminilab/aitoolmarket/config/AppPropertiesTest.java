package com.aiminilab.aitoolmarket.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppPropertiesTest {

    @Test
    void alipayPagePayDefaultsToPageCheckoutMode() {
        AppProperties.AlipayPage alipayPage = new AppProperties.AlipayPage();

        assertThat(alipayPage.getPayMode()).isEqualTo("PAGE");

        alipayPage.setPayMode("  ");

        assertThat(alipayPage.getPayMode()).isEqualTo("PAGE");
    }
}
