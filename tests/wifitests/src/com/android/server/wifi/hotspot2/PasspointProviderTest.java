/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi.hotspot2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import android.net.wifi.EAPConstants;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.pps.Credential;
import android.net.wifi.hotspot2.pps.HomeSp;
import android.net.wifi.hotspot2.pps.UpdateParameter;
import android.text.TextUtils;
import android.util.Base64;

import androidx.test.filters.SmallTest;

import com.android.internal.util.ArrayUtils;
import com.android.server.wifi.FakeKeys;
import com.android.server.wifi.IMSIParameter;
import com.android.server.wifi.SIMAccessor;
import com.android.server.wifi.WifiKeyStore;
import com.android.server.wifi.hotspot2.anqp.ANQPElement;
import com.android.server.wifi.hotspot2.anqp.CellularNetwork;
import com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType;
import com.android.server.wifi.hotspot2.anqp.DomainNameElement;
import com.android.server.wifi.hotspot2.anqp.NAIRealmData;
import com.android.server.wifi.hotspot2.anqp.NAIRealmElement;
import com.android.server.wifi.hotspot2.anqp.RoamingConsortiumElement;
import com.android.server.wifi.hotspot2.anqp.ThreeGPPNetworkElement;
import com.android.server.wifi.hotspot2.anqp.eap.AuthParam;
import com.android.server.wifi.hotspot2.anqp.eap.EAPMethod;
import com.android.server.wifi.hotspot2.anqp.eap.NonEAPInnerAuth;
import com.android.server.wifi.util.InformationElementUtil.RoamingConsortium;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.Mock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Unit tests for {@link com.android.server.wifi.hotspot2.PasspointProvider}.
 */
@SmallTest
@RunWith(Parameterized.class)
public class PasspointProviderTest {
    private static final long PROVIDER_ID = 12L;
    private static final int CREATOR_UID = 1234;
    private static final String CREATOR_PACKAGE = "com.android.test";
    private static final String CA_CERTIFICATE_NAME = "CACERT_HS2_12_0";
    private static final String CA_CERTIFICATE_NAME_2 = "CACERT_HS2_12_1";
    private static final String CLIENT_CERTIFICATE_NAME = "USRCERT_HS2_12";
    private static final String CLIENT_PRIVATE_KEY_NAME = "USRPKEY_HS2_12";
    private static final String REMEDIATION_CA_CERTIFICATE_NAME = "CACERT_HS2_REMEDIATION_12";
    private static final String CA_CERTIFICATE_ALIAS = "HS2_12_0";
    private static final String CA_CERTIFICATE_ALIAS_2 = "HS2_12_1";
    private static final String CLIENT_CERTIFICATE_ALIAS = "HS2_12";
    private static final String CLIENT_PRIVATE_KEY_ALIAS = "HS2_12";
    private static final String REMEDIATION_CA_CERTIFICATE_ALIAS = "HS2_REMEDIATION_12";
    private static final String SYSTEM_CA_STORE_PATH = "/system/etc/security/cacerts";

    private static final int TEST_UPDATE_IDENTIFIER = 1234;
    private static final int TEST_USAGE_LIMIT_DATA_LIMIT = 100;
    private static final String TEST_FQDN = "test.com";
    private static final String TEST_FQDN2 = "test2.com";
    private static final String TEST_FRIENDLY_NAME = "Friendly Name";
    private static final long[] TEST_RC_OIS = new long[] {0x1234L, 0x2345L};
    private static final long[] TEST_IE_RC_OIS = new long[] {0x1234L, 0x2133L};
    private static final long[] TEST_IE_NO_MATCHED_RC_OIS = new long[] {0x2255L, 0x2133L};
    private static final Long[] TEST_ANQP_RC_OIS = new Long[] {0x1234L, 0x2133L};
    private static final String TEST_REALM = "realm.com";
    private static final String[] TEST_TRUSTED_NAME =
            new String[] {"trusted.fqdn.com", "another.fqdn.com"};
    // User credential data
    private static final String TEST_USERNAME = "username";
    private static final String TEST_PASSWORD = "password";
    // SIM credential data
    private static final int TEST_EAP_TYPE = WifiEnterpriseConfig.Eap.SIM;
    private static final int TEST_SIM_CREDENTIAL_TYPE = EAPConstants.EAP_SIM;
    private static final String TEST_IMSI = "1234567890";

    private enum CredentialType {
        USER,
        CERT,
        SIM
    }

    @Mock WifiKeyStore mKeyStore;
    @Mock SIMAccessor mSimAccessor;
    @Mock RoamingConsortium mRoamingConsortium;
    PasspointProvider mProvider;
    X509Certificate mRemediationCaCertificate;
    String mExpectedResult;

    @Parameterized.Parameters
    public static Collection rootCAConfigsForRemediation() {
        return Arrays.asList(
                new Object[][]{
                        {FakeKeys.CA_CERT0, REMEDIATION_CA_CERTIFICATE_ALIAS}, // For R2 config
                        {null, null}, // For R1 config
                });
    }

    public PasspointProviderTest(X509Certificate remediationCaCertificate, String expectedResult) {
        mRemediationCaCertificate = remediationCaCertificate;
        mExpectedResult = expectedResult;
    }

    /** Sets up test. */
    @Before
    public void setUp() throws Exception {
        initMocks(this);
        when(mRoamingConsortium.getRoamingConsortiums()).thenReturn(null);
    }

    /**
     * Helper function for creating a provider instance for testing.
     *
     * @param config The configuration associated with the provider
     * @return {@link com.android.server.wifi.hotspot2.PasspointProvider}
     */
    private PasspointProvider createProvider(PasspointConfiguration config) {
        return new PasspointProvider(config, mKeyStore, mSimAccessor, PROVIDER_ID, CREATOR_UID,
                CREATOR_PACKAGE);
    }

    /**
     * Verify that the configuration associated with the provider is the same or not the same
     * as the expected configuration.
     *
     * @param expectedConfig The expected configuration
     * @param equals Flag indicating equality or inequality check
     */
    private void verifyInstalledConfig(PasspointConfiguration expectedConfig, boolean equals) {
        PasspointConfiguration actualConfig = mProvider.getConfig();
        if (equals) {
            assertTrue(actualConfig.equals(expectedConfig));
        } else {
            assertFalse(actualConfig.equals(expectedConfig));
        }
    }

    /**
     * Helper function for creating a Domain Name ANQP element.
     *
     * @param domains List of domain names
     * @return {@link DomainNameElement}
     */
    private DomainNameElement createDomainNameElement(String[] domains) {
        return new DomainNameElement(Arrays.asList(domains));
    }

    /**
     * Helper function for creating a NAI Realm ANQP element.
     *
     * @param realm The realm of the network
     * @param eapMethodID EAP Method ID
     * @param authParam Authentication parameter
     * @return {@link NAIRealmElement}
     */
    private NAIRealmElement createNAIRealmElement(String realm, int eapMethodID,
            AuthParam authParam) {
        Map<Integer, Set<AuthParam>> authParamMap = new HashMap<>();
        if (authParam != null) {
            Set<AuthParam> authSet = new HashSet<>();
            authSet.add(authParam);
            authParamMap.put(authParam.getAuthTypeID(), authSet);
        }
        EAPMethod eapMethod = new EAPMethod(eapMethodID, authParamMap);
        NAIRealmData realmData = new NAIRealmData(Arrays.asList(new String[] {realm}),
                Arrays.asList(new EAPMethod[] {eapMethod}));
        return new NAIRealmElement(Arrays.asList(new NAIRealmData[] {realmData}));
    }

    /**
     * Helper function for creating a Roaming Consortium ANQP element.
     *
     * @param rcOIs Roaming consortium OIs
     * @return {@link RoamingConsortiumElement}
     */
    private RoamingConsortiumElement createRoamingConsortiumElement(Long[] rcOIs) {
        return new RoamingConsortiumElement(Arrays.asList(rcOIs));
    }

    /**
     * Helper function for creating a 3GPP Network ANQP element.
     *
     * @param imsiList List of IMSI to be included in a 3GPP Network
     * @return {@link ThreeGPPNetworkElement}
     */
    private ThreeGPPNetworkElement createThreeGPPNetworkElement(String[] imsiList) {
        CellularNetwork network = new CellularNetwork(Arrays.asList(imsiList));
        return new ThreeGPPNetworkElement(Arrays.asList(new CellularNetwork[] {network}));
    }

    /**
     * Helper function for generating test passpoint configuration for test cases
     *
     * @param credentialType which type credential is generated.
     * @param isLegacy if true, omit some passpoint fields to avoid breaking comparison
     *                 between passpoint configuration converted from wifi configuration
     *                 and generated passpoint configuration.
     * @return a valid passpoint configuration
     * @throws Exception
     */
    private PasspointConfiguration generateTestPasspointConfiguration(
            CredentialType credentialType, boolean isLegacy) throws Exception {
        PasspointConfiguration config = new PasspointConfiguration();
        if (!isLegacy) {
            config.setUpdateIdentifier(TEST_UPDATE_IDENTIFIER);
            config.setUsageLimitDataLimit(TEST_USAGE_LIMIT_DATA_LIMIT);
        }
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn(TEST_FQDN);
        homeSp.setFriendlyName(TEST_FRIENDLY_NAME);
        homeSp.setRoamingConsortiumOis(TEST_RC_OIS);
        config.setHomeSp(homeSp);
        Credential credential = new Credential();
        credential.setRealm(TEST_REALM);

        if (credentialType == CredentialType.USER) {
            byte[] base64EncodedPw =
                    Base64.encode(TEST_PASSWORD.getBytes(StandardCharsets.UTF_8), Base64.DEFAULT);
            String encodedPasswordStr = new String(base64EncodedPw, StandardCharsets.UTF_8);
            Credential.UserCredential userCredential = new Credential.UserCredential();
            userCredential.setEapType(EAPConstants.EAP_TTLS);
            userCredential.setNonEapInnerMethod(Credential.UserCredential.AUTH_METHOD_MSCHAPV2);
            userCredential.setUsername(TEST_USERNAME);
            userCredential.setPassword(encodedPasswordStr);
            if (!isLegacy) {
                credential.setCaCertificates(new X509Certificate[] {FakeKeys.CA_CERT0});
            }
            credential.setUserCredential(userCredential);
        } else if (credentialType == CredentialType.CERT) {
            Credential.CertificateCredential certCredential =
                    new Credential.CertificateCredential();
            if (!isLegacy) {
                certCredential.setCertSha256Fingerprint(
                        MessageDigest.getInstance("SHA-256")
                        .digest(FakeKeys.CLIENT_CERT.getEncoded()));
                credential.setCaCertificates(new X509Certificate[] {FakeKeys.CA_CERT0});
                credential.setClientPrivateKey(FakeKeys.RSA_KEY1);
                credential.setClientCertificateChain(new X509Certificate[] {FakeKeys.CLIENT_CERT});
            } else {
                certCredential.setCertType(Credential.CertificateCredential.CERT_TYPE_X509V3);
            }
            credential.setCertCredential(certCredential);
        } else if (credentialType == CredentialType.SIM) {
            Credential.SimCredential simCredential = new Credential.SimCredential();
            simCredential.setImsi(TEST_IMSI);
            simCredential.setEapType(EAPConstants.EAP_SIM);
            credential.setSimCredential(simCredential);
        }
        config.setCredential(credential);

        return config;
    }

    /**
     * Helper function for verifying wifi configuration based on passpoing configuration
     *
     * @param passpointConfig the source of wifi configuration.
     * @param wifiConfig wifi configuration be verified.
     *
     * @throws Exception
     */
    private void verifyWifiConfigWithTestData(
            PasspointConfiguration passpointConfig, WifiConfiguration wifiConfig) {
        BitSet allowedProtocols = new BitSet();
        allowedProtocols.set(WifiConfiguration.Protocol.RSN);

        HomeSp homeSp = passpointConfig.getHomeSp();
        Credential credential = passpointConfig.getCredential();

        // Need to verify field by field since WifiConfiguration doesn't
        // override equals() function.
        WifiEnterpriseConfig wifiEnterpriseConfig = wifiConfig.enterpriseConfig;
        assertEquals(homeSp.getFqdn(), wifiConfig.FQDN);
        assertEquals(homeSp.getFriendlyName(), wifiConfig.providerFriendlyName);
        assertTrue(Arrays.equals(homeSp.getRoamingConsortiumOis(),
                wifiConfig.roamingConsortiumIds));

        assertTrue(wifiConfig.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP));
        assertTrue(wifiConfig.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X));
        assertEquals(wifiConfig.updateIdentifier,
                Integer.toString(passpointConfig.getUpdateIdentifier()));
        assertEquals(allowedProtocols, wifiConfig.allowedProtocols);
        assertEquals(Integer.toString(passpointConfig.getUpdateIdentifier()),
                wifiConfig.updateIdentifier);
        assertFalse(wifiConfig.shared);
        assertEquals(credential.getRealm(), wifiEnterpriseConfig.getRealm());

        if (credential.getUserCredential() != null) {
            Credential.UserCredential userCredential = credential.getUserCredential();
            byte[] pwOctets = Base64.decode(userCredential.getPassword(), Base64.DEFAULT);
            String decodedPassword = new String(pwOctets, StandardCharsets.UTF_8);

            assertEquals("anonymous@" + credential.getRealm(),
                    wifiEnterpriseConfig.getAnonymousIdentity());
            assertEquals(WifiEnterpriseConfig.Eap.TTLS, wifiEnterpriseConfig.getEapMethod());
            switch (userCredential.getNonEapInnerMethod()) {
                case Credential.UserCredential.AUTH_METHOD_PAP:
                    assertEquals(WifiEnterpriseConfig.Phase2.PAP,
                            wifiEnterpriseConfig.getPhase2Method());
                    break;
                case Credential.UserCredential.AUTH_METHOD_MSCHAP:
                    assertEquals(WifiEnterpriseConfig.Phase2.MSCHAP,
                            wifiEnterpriseConfig.getPhase2Method());
                    break;
                case Credential.UserCredential.AUTH_METHOD_MSCHAPV2:
                    assertEquals(WifiEnterpriseConfig.Phase2.MSCHAPV2,
                            wifiEnterpriseConfig.getPhase2Method());
                    break;
            }
            assertEquals(userCredential.getUsername(), wifiEnterpriseConfig.getIdentity());
            assertEquals(decodedPassword, wifiEnterpriseConfig.getPassword());
            assertEquals(WifiConfiguration.METERED_OVERRIDE_METERED, wifiConfig.meteredOverride);

            if (!ArrayUtils.isEmpty(passpointConfig.getAaaServerTrustedNames())) {
                assertEquals(String.join(";", passpointConfig.getAaaServerTrustedNames()),
                        wifiEnterpriseConfig.getDomainSuffixMatch());
            } else {
                assertEquals(homeSp.getFqdn(), wifiEnterpriseConfig.getDomainSuffixMatch());
            }

            if (!ArrayUtils.isEmpty(passpointConfig.getAaaServerTrustedNames())) {
                assertTrue(Arrays.equals(new String[] {SYSTEM_CA_STORE_PATH},
                        wifiEnterpriseConfig.getCaCertificateAliases()));
            } else if (ArrayUtils.isEmpty(credential.getCaCertificates())) {
                assertTrue(Arrays.equals(new String[] {SYSTEM_CA_STORE_PATH},
                        wifiEnterpriseConfig.getCaCertificateAliases()));
            } else {
                assertEquals(CA_CERTIFICATE_ALIAS, wifiEnterpriseConfig.getCaCertificateAlias());
            }
        } else if (credential.getCertCredential() != null) {
            Credential.CertificateCredential certCredential = credential.getCertCredential();
            assertEquals("anonymous@" + credential.getRealm(),
                    wifiEnterpriseConfig.getAnonymousIdentity());
            assertEquals(WifiEnterpriseConfig.Eap.TLS, wifiEnterpriseConfig.getEapMethod());
            assertEquals(CLIENT_CERTIFICATE_ALIAS,
                    wifiEnterpriseConfig.getClientCertificateAlias());
            assertEquals(WifiConfiguration.METERED_OVERRIDE_METERED, wifiConfig.meteredOverride);
            // Domain suffix match
            if (ArrayUtils.isEmpty(passpointConfig.getAaaServerTrustedNames())) {
                assertEquals(homeSp.getFqdn(), wifiEnterpriseConfig.getDomainSuffixMatch());
            } else {
                assertEquals(String.join(";", passpointConfig.getAaaServerTrustedNames()),
                        wifiEnterpriseConfig.getDomainSuffixMatch());
                assertTrue(Arrays.equals(new String[] {SYSTEM_CA_STORE_PATH},
                        wifiEnterpriseConfig.getCaCertificateAliases()));
            }
            // CA certificate
            if (!ArrayUtils.isEmpty(passpointConfig.getAaaServerTrustedNames())) {
                assertTrue(Arrays.equals(new String[] {SYSTEM_CA_STORE_PATH},
                        wifiEnterpriseConfig.getCaCertificateAliases()));
            } else if (!ArrayUtils.isEmpty(credential.getCaCertificates())) {
                assertEquals(CA_CERTIFICATE_ALIAS, wifiEnterpriseConfig.getCaCertificateAlias());
            } else {
                assertTrue(Arrays.equals(new String[] {SYSTEM_CA_STORE_PATH},
                        wifiEnterpriseConfig.getCaCertificateAliases()));
            }
        } else if (credential.getSimCredential() != null) {
            Credential.SimCredential simCredential = credential.getSimCredential();
            switch (simCredential.getEapType()) {
                case EAPConstants.EAP_SIM:
                    assertEquals(WifiEnterpriseConfig.Eap.SIM,
                            wifiEnterpriseConfig.getEapMethod());
                    break;
                case EAPConstants.EAP_AKA:
                    assertEquals(WifiEnterpriseConfig.Eap.AKA,
                            wifiEnterpriseConfig.getEapMethod());
                    break;
                case EAPConstants.EAP_AKA_PRIME:
                    assertEquals(WifiEnterpriseConfig.Eap.AKA_PRIME,
                            wifiEnterpriseConfig.getEapMethod());
                    break;
            }
            assertEquals(simCredential.getImsi(), wifiEnterpriseConfig.getPlmn());
        }
    }

    /**
     * Verify that modification to the configuration used for creating PasspointProvider
     * will not change the configuration stored inside the PasspointProvider.
     *
     * @throws Exception
     */
    @Test
    public void verifyModifyOriginalConfig() throws Exception {
        // Create a dummy PasspointConfiguration.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.USER, false);
        mProvider = createProvider(config);
        verifyInstalledConfig(config, true);

        // Modify the original configuration, the configuration maintained by the provider
        // should be unchanged.
        config.getHomeSp().setFqdn(TEST_FQDN2);
        verifyInstalledConfig(config, false);
    }

    /**
     * Verify that modification to the configuration retrieved from the PasspointProvider
     * will not change the configuration stored inside the PasspointProvider.
     *
     * @throws Exception
     */
    @Test
    public void verifyModifyRetrievedConfig() throws Exception {
        // Create a dummy PasspointConfiguration.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.USER, false);
        mProvider = createProvider(config);
        verifyInstalledConfig(config, true);

        // Modify the retrieved configuration, verify the configuration maintained by the
        // provider should be unchanged.
        PasspointConfiguration retrievedConfig = mProvider.getConfig();
        retrievedConfig.getHomeSp().setFqdn(TEST_FQDN2);
        verifyInstalledConfig(retrievedConfig, false);
    }

    /**
     * Verify a successful installation of certificates and key.
     *
     * @throws Exception
     */
    @Test
    public void installCertsAndKeysSuccess() throws Exception {
        // Create a dummy configuration with certificate credential.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.CERT, false);
        Credential credential = config.getCredential();
        Credential.CertificateCredential certCredential = credential.getCertCredential();
        credential.setCaCertificates(new X509Certificate[]{FakeKeys.CA_CERT0, FakeKeys.CA_CERT1});
        if (mRemediationCaCertificate != null) {
            UpdateParameter updateParameter = new UpdateParameter();
            updateParameter.setCaCertificate(mRemediationCaCertificate);
            config.setSubscriptionUpdate(updateParameter);
        }
        mProvider = createProvider(config);

        // Install client certificate and key to the keystore successfully.
        when(mKeyStore.putCertInKeyStore(CA_CERTIFICATE_NAME, FakeKeys.CA_CERT0))
                .thenReturn(true);
        when(mKeyStore.putCertInKeyStore(CA_CERTIFICATE_NAME_2, FakeKeys.CA_CERT1))
                .thenReturn(true);
        when(mKeyStore.putKeyInKeyStore(CLIENT_PRIVATE_KEY_NAME, FakeKeys.RSA_KEY1))
                .thenReturn(true);
        when(mKeyStore.putCertInKeyStore(CLIENT_CERTIFICATE_NAME, FakeKeys.CLIENT_CERT))
                .thenReturn(true);
        when(mKeyStore.putCertInKeyStore(REMEDIATION_CA_CERTIFICATE_NAME, FakeKeys.CA_CERT0))
                .thenReturn(true);
        assertTrue(mProvider.installCertsAndKeys());

        // Verify client certificate and key in the configuration gets cleared and aliases
        // are set correctly.
        PasspointConfiguration curConfig = mProvider.getConfig();
        assertTrue(curConfig.getCredential().getCaCertificates() == null);
        assertTrue(curConfig.getCredential().getClientPrivateKey() == null);
        assertTrue(curConfig.getCredential().getClientCertificateChain() == null);
        if (mRemediationCaCertificate != null) {
            assertTrue(curConfig.getSubscriptionUpdate().getCaCertificate() == null);
        }
        assertTrue(mProvider.getCaCertificateAliases().equals(
                Arrays.asList(CA_CERTIFICATE_ALIAS, CA_CERTIFICATE_ALIAS_2)));
        assertTrue(mProvider.getClientPrivateKeyAlias().equals(CLIENT_PRIVATE_KEY_ALIAS));
        assertTrue(mProvider.getClientCertificateAlias().equals(CLIENT_CERTIFICATE_ALIAS));
        assertTrue(TextUtils.equals(mProvider.getRemediationCaCertificateAlias(), mExpectedResult));
    }

    /**
     * Verify a failure installation of certificates and key.
     */
    @Test
    public void installCertsAndKeysFailure() throws Exception {
        // Create a dummy configuration with certificate credential.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.CERT, false);
        Credential credential = config.getCredential();
        Credential.CertificateCredential certCredential = credential.getCertCredential();
        credential.setCaCertificates(new X509Certificate[]{FakeKeys.CA_CERT0, FakeKeys.CA_CERT1});
        config.setCredential(credential);

        UpdateParameter updateParameter = new UpdateParameter();
        updateParameter.setCaCertificate(mRemediationCaCertificate);
        config.setSubscriptionUpdate(updateParameter);
        mProvider = createProvider(config);

        // Failed to install client certificate to the keystore.
        when(mKeyStore.putCertInKeyStore(CA_CERTIFICATE_NAME, FakeKeys.CA_CERT0))
                .thenReturn(true);
        when(mKeyStore.putCertInKeyStore(CA_CERTIFICATE_NAME_2, FakeKeys.CA_CERT1))
                .thenReturn(false);
        when(mKeyStore.putKeyInKeyStore(CLIENT_PRIVATE_KEY_NAME, FakeKeys.RSA_KEY1))
                .thenReturn(true);
        when(mKeyStore.putCertInKeyStore(CLIENT_CERTIFICATE_NAME, FakeKeys.CLIENT_CERT))
                .thenReturn(true);
        when(mKeyStore.putCertInKeyStore(REMEDIATION_CA_CERTIFICATE_NAME, FakeKeys.CA_CERT0))
                .thenReturn(true);
        assertFalse(mProvider.installCertsAndKeys());

        // Verify certificates and key in the configuration are not cleared and aliases
        // are not set.
        PasspointConfiguration curConfig = mProvider.getConfig();
        assertTrue(curConfig.getCredential().getCaCertificates() != null);
        assertTrue(curConfig.getCredential().getClientCertificateChain() != null);
        assertTrue(curConfig.getCredential().getClientPrivateKey() != null);
        if (mRemediationCaCertificate != null) {
            assertTrue(curConfig.getSubscriptionUpdate().getCaCertificate() != null);
        }
        assertTrue(mProvider.getCaCertificateAliases() == null);
        assertTrue(mProvider.getClientPrivateKeyAlias() == null);
        assertTrue(mProvider.getClientCertificateAlias() == null);
        assertTrue(mProvider.getRemediationCaCertificateAlias() == null);
    }

    /**
     * Verify a successful uninstallation of certificates and key.
     */
    @Test
    public void uninstallCertsAndKeys() throws Exception {
        // Create a dummy configuration with certificate credential.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.CERT, false);
        Credential credential = config.getCredential();
        Credential.CertificateCredential certCredential = credential.getCertCredential();
        credential.setCaCertificates(new X509Certificate[]{FakeKeys.CA_CERT0, FakeKeys.CA_CERT1});
        config.setCredential(credential);
        if (mRemediationCaCertificate != null) {
            UpdateParameter updateParameter = new UpdateParameter();
            updateParameter.setCaCertificate(FakeKeys.CA_CERT0);
            config.setSubscriptionUpdate(updateParameter);
        }
        mProvider = createProvider(config);

        // Install client certificate and key to the keystore successfully.
        when(mKeyStore.putCertInKeyStore(CA_CERTIFICATE_NAME, FakeKeys.CA_CERT0))
                .thenReturn(true);
        when(mKeyStore.putCertInKeyStore(CA_CERTIFICATE_NAME_2, FakeKeys.CA_CERT1))
                .thenReturn(true);
        when(mKeyStore.putKeyInKeyStore(CLIENT_PRIVATE_KEY_NAME, FakeKeys.RSA_KEY1))
                .thenReturn(true);
        when(mKeyStore.putCertInKeyStore(CLIENT_CERTIFICATE_NAME, FakeKeys.CLIENT_CERT))
                .thenReturn(true);
        when(mKeyStore.putCertInKeyStore(REMEDIATION_CA_CERTIFICATE_NAME, FakeKeys.CA_CERT0))
                .thenReturn(true);
        assertTrue(mProvider.installCertsAndKeys());
        assertTrue(mProvider.getCaCertificateAliases().equals(
                Arrays.asList(CA_CERTIFICATE_ALIAS, CA_CERTIFICATE_ALIAS_2)));
        assertTrue(mProvider.getClientPrivateKeyAlias().equals(CLIENT_PRIVATE_KEY_ALIAS));
        assertTrue(mProvider.getClientCertificateAlias().equals(CLIENT_CERTIFICATE_ALIAS));
        assertTrue(TextUtils.equals(mProvider.getRemediationCaCertificateAlias(), mExpectedResult));

        // Uninstall certificates and key from the keystore.
        mProvider.uninstallCertsAndKeys();
        verify(mKeyStore).removeEntryFromKeyStore(CA_CERTIFICATE_NAME);
        verify(mKeyStore).removeEntryFromKeyStore(CA_CERTIFICATE_NAME_2);
        verify(mKeyStore).removeEntryFromKeyStore(CLIENT_CERTIFICATE_NAME);
        verify(mKeyStore).removeEntryFromKeyStore(CLIENT_PRIVATE_KEY_NAME);
        if (mRemediationCaCertificate != null) {
            verify(mKeyStore).removeEntryFromKeyStore(REMEDIATION_CA_CERTIFICATE_NAME);
        }

        assertTrue(mProvider.getCaCertificateAliases() == null);
        assertTrue(mProvider.getClientPrivateKeyAlias() == null);
        assertTrue(mProvider.getClientCertificateAlias() == null);
        assertTrue(mProvider.getRemediationCaCertificateAlias() == null);
    }

    /**
     * Verify that a provider is a home provider when its FQDN matches a domain name in the
     * Domain Name ANQP element and no NAI realm is provided.
     *
     * @throws Exception
     */
    @Test
    public void matchFQDNWithoutNAIRealm() throws Exception {
        // Setup test provider.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.USER, false);
        mProvider = createProvider(config);

        // Setup ANQP elements.
        Map<ANQPElementType, ANQPElement> anqpElementMap = new HashMap<>();
        anqpElementMap.put(ANQPElementType.ANQPDomName,
                createDomainNameElement(new String[] {TEST_FQDN}));

        assertEquals(PasspointMatch.HomeProvider,
            mProvider.match(anqpElementMap, mRoamingConsortium));
    }

    /**
     * Verify that a provider is a home provider when its FQDN matches a domain name in the
     * Domain Name ANQP element and the provider's credential matches the NAI realm provided.
     *
     * @throws Exception
     */
    @Test
    public void matchFQDNWithNAIRealmMatch() throws Exception {
        // Setup test provider.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.USER, false);
        mProvider = createProvider(config);

        // Setup Domain Name ANQP element.
        Map<ANQPElementType, ANQPElement> anqpElementMap = new HashMap<>();
        anqpElementMap.put(ANQPElementType.ANQPDomName,
                createDomainNameElement(new String[] {TEST_FQDN}));
        anqpElementMap.put(ANQPElementType.ANQPNAIRealm,
                createNAIRealmElement(TEST_REALM, EAPConstants.EAP_TTLS,
                        new NonEAPInnerAuth(NonEAPInnerAuth.AUTH_TYPE_MSCHAPV2)));

        assertEquals(PasspointMatch.HomeProvider,
            mProvider.match(anqpElementMap, mRoamingConsortium));
    }

    /**
     * Verify that there is no match when the provider's FQDN matches a domain name in the
     * Domain Name ANQP element but the provider's credential doesn't match the authentication
     * method provided in the NAI realm.
     *
     * @throws Exception
     */
    @Test
    public void matchFQDNWithNAIRealmMismatch() throws Exception {
        // Setup test provider.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.USER, false);
        mProvider = createProvider(config);

        // Setup Domain Name ANQP element.
        Map<ANQPElementType, ANQPElement> anqpElementMap = new HashMap<>();
        anqpElementMap.put(ANQPElementType.ANQPDomName,
                createDomainNameElement(new String[] {TEST_FQDN}));
        anqpElementMap.put(ANQPElementType.ANQPNAIRealm,
                createNAIRealmElement(TEST_REALM, EAPConstants.EAP_TLS, null));

        assertEquals(PasspointMatch.None, mProvider.match(anqpElementMap, mRoamingConsortium));
    }

    /**
     * Verify that a provider is a home provider when its SIM credential matches an 3GPP network
     * domain name in the Domain Name ANQP element.
     *
     * @throws Exception
     */
    @Test
    public void matchFQDNWith3GPPNetworkDomainName() throws Exception {
        // Setup test provider.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.SIM, false);
        when(mSimAccessor.getMatchingImsis(new IMSIParameter(TEST_IMSI, false)))
                .thenReturn(Arrays.asList(new String[] {TEST_IMSI}));
        mProvider = createProvider(config);

        // Setup Domain Name ANQP element.
        Map<ANQPElementType, ANQPElement> anqpElementMap = new HashMap<>();
        anqpElementMap.put(ANQPElementType.ANQPDomName,
                createDomainNameElement(new String[] {"wlan.mnc456.mcc123.3gppnetwork.org"}));

        assertEquals(PasspointMatch.HomeProvider,
            mProvider.match(anqpElementMap, mRoamingConsortium));
    }

    /**
     * Verify that a provider is a home provider when its FQDN, roaming consortium OI, and
     * IMSI all matched against the ANQP elements, since we prefer matching home provider over
     * roaming provider.
     *
     * @throws Exception
     */
    @Test
    public void matchFQDNOverRoamingProvider() throws Exception {
        // Setup test provider.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.SIM, false);
        when(mSimAccessor.getMatchingImsis(new IMSIParameter(TEST_IMSI, false)))
                .thenReturn(Arrays.asList(new String[] {TEST_IMSI}));
        mProvider = createProvider(config);

        // Setup ANQP elements.
        Map<ANQPElementType, ANQPElement> anqpElementMap = new HashMap<>();
        anqpElementMap.put(ANQPElementType.ANQPDomName,
                createDomainNameElement(new String[] {TEST_FQDN}));
        anqpElementMap.put(ANQPElementType.ANQPRoamingConsortium,
                createRoamingConsortiumElement(TEST_ANQP_RC_OIS));
        anqpElementMap.put(ANQPElementType.ANQP3GPPNetwork,
                createThreeGPPNetworkElement(new String[] {"123456"}));

        assertEquals(PasspointMatch.HomeProvider,
                mProvider.match(anqpElementMap, mRoamingConsortium));
    }

    /**
     * Verify that a provider is a roaming provider when a roaming consortium OI matches an OI
     * in the roaming consortium ANQP element and no NAI realm is provided.
     *
     * @throws Exception
     */
    @Test
    public void matchRoamingConsortiumWithoutNAIRealm() throws Exception {
        // Setup test provider.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.SIM, false);
        mProvider = createProvider(config);

        // Setup Roaming Consortium ANQP element.
        Map<ANQPElementType, ANQPElement> anqpElementMap = new HashMap<>();
        anqpElementMap.put(ANQPElementType.ANQPRoamingConsortium,
                createRoamingConsortiumElement(TEST_ANQP_RC_OIS));

        assertEquals(PasspointMatch.RoamingProvider,
            mProvider.match(anqpElementMap, mRoamingConsortium));
    }

    /**
     * Verify that a provider is a roaming provider when a roaming consortium OI matches an OI
     * in the roaming consortium ANQP element and the provider's credential matches the
     * NAI realm provided.
     *
     * @throws Exception
     */
    @Test
    public void matchRoamingConsortiumWithNAIRealmMatch() throws Exception {

        // Setup test provider.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.USER, false);
        mProvider = createProvider(config);

        // Setup Roaming Consortium ANQP element.
        Map<ANQPElementType, ANQPElement> anqpElementMap = new HashMap<>();
        anqpElementMap.put(ANQPElementType.ANQPRoamingConsortium,
                createRoamingConsortiumElement(TEST_ANQP_RC_OIS));
        anqpElementMap.put(ANQPElementType.ANQPNAIRealm,
                createNAIRealmElement(TEST_REALM, EAPConstants.EAP_TTLS,
                        new NonEAPInnerAuth(NonEAPInnerAuth.AUTH_TYPE_MSCHAPV2)));

        assertEquals(PasspointMatch.RoamingProvider,
                mProvider.match(anqpElementMap, mRoamingConsortium));
    }

    /**
     * Verify that there is no match when a roaming consortium OI matches an OI
     * in the roaming consortium ANQP element and but NAI realm is not matched.
     *
     * @throws Exception
     */
    @Test
    public void matchRoamingConsortiumWithNAIRealmMisMatch() throws Exception {
        // Setup test provider.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.USER, false);
        mProvider = createProvider(config);

        // Setup Roaming Consortium ANQP element.
        Map<ANQPElementType, ANQPElement> anqpElementMap = new HashMap<>();
        anqpElementMap.put(ANQPElementType.ANQPRoamingConsortium,
                createRoamingConsortiumElement(TEST_ANQP_RC_OIS));
        // Set up NAI with different EAP method
        anqpElementMap.put(ANQPElementType.ANQPNAIRealm,
                createNAIRealmElement(TEST_REALM, EAPConstants.EAP_TLS, null));

        assertEquals(PasspointMatch.None,
                mProvider.match(anqpElementMap, mRoamingConsortium));
    }

    /**
     * Verify that a provider is a roaming provider when a roaming consortium OI matches an OI
     * in the roaming consortium information element and no NAI realm is provided.
     *
     * @throws Exception
     */
    @Test
    public void matchRoamingConsortiumIeWithoutNAIRealm() throws Exception {
        // Setup test provider.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.USER, false);
        mProvider = createProvider(config);

        // Setup Roaming Consortium ANQP element.
        Map<ANQPElementType, ANQPElement> anqpElementMap = new HashMap<>();

        // Setup Roaming Consortium Information element.
        when(mRoamingConsortium.getRoamingConsortiums()).thenReturn(TEST_IE_RC_OIS);

        assertEquals(PasspointMatch.RoamingProvider,
            mProvider.match(anqpElementMap, mRoamingConsortium));
    }

    /**
     * Verify that a provider is a roaming provider when a roaming consortium OI matches an OI
     * in the roaming consortium information element and the provider's credential matches the
     * NAI realm provided.
     *
     * @throws Exception
     */
    @Test
    public void matchRoamingConsortiumIeWithNAIRealmMatch() throws Exception {
        // Setup test provider.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.USER, false);
        mProvider = createProvider(config);

        // Setup Roaming Consortium ANQP element.
        Map<ANQPElementType, ANQPElement> anqpElementMap = new HashMap<>();

        // Setup Roaming Consortium Information element.
        when(mRoamingConsortium.getRoamingConsortiums()).thenReturn(TEST_IE_RC_OIS);
        anqpElementMap.put(ANQPElementType.ANQPNAIRealm,
                createNAIRealmElement(TEST_REALM, EAPConstants.EAP_TTLS,
                        new NonEAPInnerAuth(NonEAPInnerAuth.AUTH_TYPE_MSCHAPV2)));

        assertEquals(PasspointMatch.RoamingProvider,
                mProvider.match(anqpElementMap, mRoamingConsortium));
    }

    /**
     * Verify that there is no match when a roaming consortium OI matches an OI
     * in the roaming consortium information element, but NAI realm is not matched.
     *
     * @throws Exception
     */
    @Test
    public void matchRoamingConsortiumIeWithNAIRealmMismatch() throws Exception {
        // Setup test provider.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.USER, false);
        mProvider = createProvider(config);

        // Setup Roaming Consortium ANQP element.
        Map<ANQPElementType, ANQPElement> anqpElementMap = new HashMap<>();

        // Setup Roaming Consortium Information element.
        when(mRoamingConsortium.getRoamingConsortiums()).thenReturn(TEST_IE_RC_OIS);
        // Set up NAI with different EAP method
        anqpElementMap.put(ANQPElementType.ANQPNAIRealm,
                createNAIRealmElement(TEST_REALM, EAPConstants.EAP_TLS, null));

        assertEquals(PasspointMatch.None,
                mProvider.match(anqpElementMap, mRoamingConsortium));
    }

    /**
     * Verify that none of matched providers are found when a roaming consortium OI doesn't
     * matches an OI in the roaming consortium information element and
     * none of NAI realms match each other.
     *
     * @throws Exception
     */
    @Test
    public void misMatchForRoamingConsortiumIeAndNAIRealm() throws Exception {
        // Setup test provider.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.USER, false);
        mProvider = createProvider(config);

        // Setup Roaming Consortium ANQP element.
        Map<ANQPElementType, ANQPElement> anqpElementMap = new HashMap<>();

        // Setup Roaming Consortium Information element.
        when(mRoamingConsortium.getRoamingConsortiums()).thenReturn(TEST_IE_NO_MATCHED_RC_OIS);

        assertEquals(PasspointMatch.None,
            mProvider.match(anqpElementMap, mRoamingConsortium));
    }

    /**
     * Verify that a provider is a roaming provider when the provider's IMSI parameter and an IMSI
     * from the SIM card matches a MCC-MNC in the 3GPP Network ANQP element regardless of NAI realm
     * mismatch.
     *
     * @throws Exception
     */
    @Test
    public void matchThreeGPPNetworkWithNAIRealmMismatch() throws Exception {
        // Setup test provider.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.SIM, false);
        when(mSimAccessor.getMatchingImsis(new IMSIParameter(TEST_IMSI, false)))
                .thenReturn(Arrays.asList(new String[] {TEST_IMSI}));
        mProvider = createProvider(config);

        // Setup 3GPP Network ANQP element.
        Map<ANQPElementType, ANQPElement> anqpElementMap = new HashMap<>();
        anqpElementMap.put(ANQPElementType.ANQP3GPPNetwork,
                createThreeGPPNetworkElement(new String[] {"123456"}));

        // Setup NAI Realm ANQP element with different realm.
        anqpElementMap.put(ANQPElementType.ANQPNAIRealm,
                createNAIRealmElement(TEST_REALM, EAPConstants.EAP_TTLS,
                new NonEAPInnerAuth(NonEAPInnerAuth.AUTH_TYPE_MSCHAPV2)));

        assertEquals(PasspointMatch.RoamingProvider,
            mProvider.match(anqpElementMap, mRoamingConsortium));
    }

    /**
     * Verify that a provider is a roaming provider when the provider's IMSI parameter and an IMSI
     * from the SIM card matches a MCC-MNC in the 3GPP Network ANQP element regardless of NAI realm
     * match.
     *
     * @throws Exception
     */
    @Test
    public void matchThreeGPPNetworkWithNAIRealmMatch() throws Exception {
        // Setup test provider.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.SIM, false);
        when(mSimAccessor.getMatchingImsis(new IMSIParameter(TEST_IMSI, false)))
                .thenReturn(Arrays.asList(new String[] {TEST_IMSI}));
        mProvider = createProvider(config);

        // Setup 3GPP Network ANQP element.
        Map<ANQPElementType, ANQPElement> anqpElementMap = new HashMap<>();
        anqpElementMap.put(ANQPElementType.ANQP3GPPNetwork,
                createThreeGPPNetworkElement(new String[] {"123456"}));

        // Setup NAI Realm ANQP element with same realm.
        anqpElementMap.put(ANQPElementType.ANQPNAIRealm,
                createNAIRealmElement(TEST_REALM, EAPConstants.EAP_AKA, null));

        assertEquals(PasspointMatch.RoamingProvider,
                mProvider.match(anqpElementMap, mRoamingConsortium));
    }

    /**
     * Verify that a provider is a roaming provider when its credential only matches a NAI realm in
     * the NAI Realm ANQP element and not match for Domain Name, RoamingConsortium and 3GPP.
     *
     * @throws Exception
     */
    @Test
    public void matchOnlyNAIRealmWithOtherInformationMismatch() throws Exception {
        // Setup test provider.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.USER, false);
        mProvider = createProvider(config);

        // Setup NAI Realm ANQP element.
        Map<ANQPElementType, ANQPElement> anqpElementMap = new HashMap<>();
        anqpElementMap.put(ANQPElementType.ANQPNAIRealm,
                createNAIRealmElement(TEST_REALM, EAPConstants.EAP_TTLS,
                        new NonEAPInnerAuth(NonEAPInnerAuth.AUTH_TYPE_MSCHAPV2)));

        assertEquals(PasspointMatch.RoamingProvider,
            mProvider.match(anqpElementMap, mRoamingConsortium));
    }

    /**
     * Verify that an expected WifiConfiguration will be returned for a Passpoint provider
     * with a user credential.
     *
     * @throws Exception
     */
    @Test
    public void getWifiConfigWithUserCredential() throws Exception {
        // Create provider for R2.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.USER, false);
        mProvider = createProvider(config);

        // Install certificate.
        when(mKeyStore.putCertInKeyStore(CA_CERTIFICATE_NAME, FakeKeys.CA_CERT0))
                .thenReturn(true);
        assertTrue(mProvider.installCertsAndKeys());

        // Retrieve the WifiConfiguration associated with the provider, and verify the content of
        // the configuration.
        verifyWifiConfigWithTestData(config, mProvider.getWifiConfig());

        // Verify that AAA server trusted names are provisioned.
        config.setAaaServerTrustedNames(TEST_TRUSTED_NAME);
        mProvider = createProvider(config);
        verifyWifiConfigWithTestData(config,
                createProvider(config).getWifiConfig());
    }

    /**
     * Verify that an expected WifiConfiguration will be returned for a Passpoint provider
     * with a user credential which has AAA server trusted names provisioned.
     *
     * @throws Exception
     */
    @Test
    public void getWifiConfigWithUserCredentialHasAaaServerTrustedNames() throws Exception {
        // Create provider for R2.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.USER, false);
        config.setAaaServerTrustedNames(TEST_TRUSTED_NAME);
        mProvider = createProvider(config);

        // Install certificate.
        when(mKeyStore.putCertInKeyStore(CA_CERTIFICATE_NAME, FakeKeys.CA_CERT0))
                .thenReturn(true);
        assertTrue(mProvider.installCertsAndKeys());

        // Retrieve the WifiConfiguration associated with the provider, and verify the content of
        // the configuration.
        verifyWifiConfigWithTestData(config, mProvider.getWifiConfig());

        // Verify that AAA server trusted names are provisioned.
        config.setAaaServerTrustedNames(TEST_TRUSTED_NAME);
        mProvider = createProvider(config);
        verifyWifiConfigWithTestData(config,
                createProvider(config).getWifiConfig());
    }

    /**
     * Verify that an expected WifiConfiguration will be returned for a Passpoint provider
     * with a user credential which has no CA cert.
     *
     * @throws Exception
     */
    @Test
    public void getWifiConfigWithUserCredentialNoCaCert() throws Exception {
        // Create provider for R2.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.USER, false);
        config.getCredential().setCaCertificates(null);
        mProvider = createProvider(config);

        // Retrieve the WifiConfiguration associated with the provider, and verify the content of
        // the configuration.
        verifyWifiConfigWithTestData(config, mProvider.getWifiConfig());
    }

    /**
     * Verify that an expected WifiConfiguration will be returned for a Passpoint provider
     * with a certificate credential.
     *
     * @throws Exception
     */
    @Test
    public void getWifiConfigWithCertCredential() throws Exception {
        // Create provider.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.CERT, false);
        mProvider = createProvider(config);

        // Install certificate.
        when(mKeyStore.putCertInKeyStore(CA_CERTIFICATE_NAME, FakeKeys.CA_CERT0))
                .thenReturn(true);
        when(mKeyStore.putKeyInKeyStore(CLIENT_PRIVATE_KEY_NAME, FakeKeys.RSA_KEY1))
                .thenReturn(true);
        when(mKeyStore.putCertInKeyStore(CLIENT_CERTIFICATE_NAME, FakeKeys.CLIENT_CERT))
                .thenReturn(true);
        assertTrue(mProvider.installCertsAndKeys());

        // Retrieve the WifiConfiguration associated with the provider, and verify the content of
        // the configuration.
        verifyWifiConfigWithTestData(config, mProvider.getWifiConfig());
    }

    /**
     * Verify that an expected WifiConfiguration will be returned for a Passpoint provider
     * with a certificate credential which has AAA server trusted names provisioned.
     *
     * @throws Exception
     */
    @Test
    public void getWifiConfigWithCertCredentialHasAaaServerTrustedNames() throws Exception {
        // Create provider.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.CERT, false);
        config.setAaaServerTrustedNames(TEST_TRUSTED_NAME);
        mProvider = createProvider(config);

        // Install certificate.
        when(mKeyStore.putCertInKeyStore(CA_CERTIFICATE_NAME, FakeKeys.CA_CERT0))
                .thenReturn(true);
        when(mKeyStore.putKeyInKeyStore(CLIENT_PRIVATE_KEY_NAME, FakeKeys.RSA_KEY1))
                .thenReturn(true);
        when(mKeyStore.putCertInKeyStore(CLIENT_CERTIFICATE_NAME, FakeKeys.CLIENT_CERT))
                .thenReturn(true);
        assertTrue(mProvider.installCertsAndKeys());

        // Retrieve the WifiConfiguration associated with the provider, and verify the content of
        // the configuration.
        verifyWifiConfigWithTestData(config, mProvider.getWifiConfig());
    }

    /**
     * Verify that an expected WifiConfiguration will be returned for a Passpoint provider
     * with a certificate credential which has no CA cert.
     *
     * @throws Exception
     */
    @Test
    public void getWifiConfigWithCertCredentialNoCaCert() throws Exception {
        // Create provider.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.CERT, false);
        config.getCredential().setCaCertificates(null);
        mProvider = createProvider(config);

        // Install certificate.
        when(mKeyStore.putKeyInKeyStore(CLIENT_PRIVATE_KEY_NAME, FakeKeys.RSA_KEY1))
                .thenReturn(true);
        when(mKeyStore.putCertInKeyStore(CLIENT_CERTIFICATE_NAME, FakeKeys.CLIENT_CERT))
                .thenReturn(true);
        assertTrue(mProvider.installCertsAndKeys());

        // Retrieve the WifiConfiguration associated with the provider, and verify the content of
        // the configuration.
        verifyWifiConfigWithTestData(config, mProvider.getWifiConfig());
    }

    /**
     * Verify that an expected WifiConfiguration will be returned for a Passpoint provider
     * with a SIM credential.
     *
     * @throws Exception
     */
    @Test
    public void getWifiConfigWithSimCredential() throws Exception {
        // Create provider.
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.SIM, false);
        mProvider = createProvider(config);

        // Retrieve the WifiConfiguration associated with the provider, and verify the content of
        // the configuration.
        verifyWifiConfigWithTestData(config, mProvider.getWifiConfig());
    }

    /**
     * Verify that an expected {@link PasspointConfiguration} will be returned when converting
     * from a {@link WifiConfiguration} containing a user credential.
     *
     * @throws Exception
     */
    @Test
    public void convertFromWifiConfigWithUserCredential() throws Exception {
        // Setup WifiConfiguration for legacy Passpoint configuraiton.
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = TEST_FQDN;
        wifiConfig.providerFriendlyName = TEST_FRIENDLY_NAME;
        wifiConfig.roamingConsortiumIds = TEST_RC_OIS;
        wifiConfig.enterpriseConfig.setIdentity(TEST_USERNAME);
        wifiConfig.enterpriseConfig.setPassword(TEST_PASSWORD);
        wifiConfig.enterpriseConfig.setRealm(TEST_REALM);
        wifiConfig.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TTLS);
        wifiConfig.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.PAP);

        // Setup expected {@link PasspointConfiguration}
        PasspointConfiguration passpointConfig = generateTestPasspointConfiguration(
                CredentialType.USER, true);
        Credential.UserCredential userCredential =
                passpointConfig.getCredential().getUserCredential();
        userCredential.setNonEapInnerMethod(Credential.UserCredential.AUTH_METHOD_PAP);

        assertEquals(passpointConfig, PasspointProvider.convertFromWifiConfig(wifiConfig));
    }

    /**
     * Verify that an expected {@link PasspointConfiguration} will be returned when converting
     * from a {@link WifiConfiguration} containing a SIM credential.
     *
     * @throws Exception
     */
    @Test
    public void convertFromWifiConfigWithSimCredential() throws Exception {
        // Setup WifiConfiguration for legacy Passpoint configuraiton.
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = TEST_FQDN;
        wifiConfig.providerFriendlyName = TEST_FRIENDLY_NAME;
        wifiConfig.roamingConsortiumIds = TEST_RC_OIS;
        wifiConfig.enterpriseConfig.setRealm(TEST_REALM);
        wifiConfig.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.SIM);
        wifiConfig.enterpriseConfig.setPlmn(TEST_IMSI);

        // Setup expected {@link PasspointConfiguration}
        PasspointConfiguration passpointConfig = generateTestPasspointConfiguration(
                CredentialType.SIM, true);

        assertEquals(passpointConfig, PasspointProvider.convertFromWifiConfig(wifiConfig));
    }

    /**
     * Verify that an expected {@link PasspointConfiguration} will be returned when converting
     * from a {@link WifiConfiguration} containing a certificate credential.
     *
     * @throws Exception
     */
    @Test
    public void convertFromWifiConfigWithCertCredential() throws Exception {
        // Setup WifiConfiguration for legacy Passpoint configuraiton.
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = TEST_FQDN;
        wifiConfig.providerFriendlyName = TEST_FRIENDLY_NAME;
        wifiConfig.roamingConsortiumIds = TEST_RC_OIS;
        wifiConfig.enterpriseConfig.setRealm(TEST_REALM);
        wifiConfig.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TLS);

        // Setup expected {@link PasspointConfiguration}
        PasspointConfiguration passpointConfig = generateTestPasspointConfiguration(
                CredentialType.CERT, true);

        assertEquals(passpointConfig, PasspointProvider.convertFromWifiConfig(wifiConfig));
    }

    /**
     * Verify that {@link PasspointProvider#isSimCredential} will return true for provider that's
     * backed by a SIM credential.
     *
     * @throws Exception
     */
    @Test
    public void providerBackedBySimCredential() throws Exception {
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.SIM, false);
        mProvider = createProvider(config);

        assertTrue(mProvider.isSimCredential());
    }

    /**
     * Verify that {@link PasspointProvider#isSimCredential} will return false for provider that's
     * not backed by a SIM credential.
     *
     * @throws Exception
     */
    @Test
    public void providerNotBackedBySimCredential() throws Exception {
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.CERT, false);
        mProvider = createProvider(config);

        assertFalse(mProvider.isSimCredential());
    }

    /**
     * Verify that hasEverConnected flag is set correctly using
     * {@link PasspointProvider#setHasEverConnected}.
     *
     * @throws Exception
     */
    @Test
    public void setHasEverConnected() throws Exception {
        PasspointConfiguration config = generateTestPasspointConfiguration(
                CredentialType.USER, false);
        mProvider = createProvider(config);
        verifyInstalledConfig(config, true);

        assertFalse(mProvider.getHasEverConnected());
        mProvider.setHasEverConnected(true);
        assertTrue(mProvider.getHasEverConnected());
    }
}
