/*
 * Copyright 2018 The Android Open Source Project
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

import static com.android.server.wifi.hotspot2.ASN1SubjectAltNamesParser
        .ID_WFA_OID_HOTSPOT_FRIENDLYNAME;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import android.support.test.filters.SmallTest;
import android.util.Pair;

import com.android.org.bouncycastle.asn1.ASN1EncodableVector;
import com.android.org.bouncycastle.asn1.ASN1ObjectIdentifier;
import com.android.org.bouncycastle.asn1.DEROctetString;
import com.android.org.bouncycastle.asn1.DERSequence;
import com.android.org.bouncycastle.asn1.DERTaggedObject;
import com.android.org.bouncycastle.asn1.DERUTF8String;
import com.android.org.bouncycastle.asn1.x509.GeneralName;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Unit tests for {@link com.android.server.wifi.hotspot2.ASN1SubjectAltNamesParser}.
 */
@SmallTest
public class ASN1SubjectAltNamesParserTest {
    private List<List<?>> mNewNames;
    private static final String LOCAL_HOST_NAME = "localhost";
    private static final byte[] LOCAL_HOST_ADDRESS = {127, 0, 0, 1};
    private static final String TEST_FRIENDLY_NAME = "Boingo";
    private static final String TEST_LANGUAGE = "eng";
    private static final Locale TEST_LOCALE = new Locale.Builder().setLanguage(
            TEST_LANGUAGE).build();
    private static final Pair<Locale, String> EXPECTED_RESULT = Pair.create(TEST_LOCALE,
            TEST_FRIENDLY_NAME);
    private ASN1SubjectAltNamesParser mASN1SubjectAltNamesParser;
    private static final ASN1ObjectIdentifier WFA_OID_HOTSPOT_FRIENDLYNAME =
            (new ASN1ObjectIdentifier(ID_WFA_OID_HOTSPOT_FRIENDLYNAME));
    @Mock
    private X509Certificate mX509Certificate;

     /**Sets up test. */
    @Before
    public void setUp() throws Exception {
        initMocks(this);
        mNewNames = new ArrayList<>();
        mASN1SubjectAltNamesParser = ASN1SubjectAltNamesParser.getInstance();
    }

    /**
     * Verify that getProviderNames should return empty List in case providerCert is null.
     */
    @Test
    public void testNullForProviderCertShouldReturnEmptyList() {
        assertTrue(mASN1SubjectAltNamesParser.getProviderNames(null).isEmpty());
    }

    /**
     * Verify that getProviderNames should return empty List in case providerCert doesn't have
     * SubjectAltName entry
     */
    @Test
    public void testNullFromgetSubjectAlternativeNamesShouldReturnEmptyList() throws Exception {
        when(mX509Certificate.getSubjectAlternativeNames()).thenReturn(null);
        assertTrue(mASN1SubjectAltNamesParser.getProviderNames(mX509Certificate).isEmpty());
    }

    /**
     * Verify that getProviderNames should return empty List in case providerCert return empty list
     * for SubjectAltName entry
     */
    @Test
    public void testEmptyListFromGetSubjectAlternativeNamesShouldReturnEmptyList()
            throws Exception {
        when(mX509Certificate.getSubjectAlternativeNames()).thenReturn(Collections.emptySet());
        assertTrue(mASN1SubjectAltNamesParser.getProviderNames(mX509Certificate).isEmpty());
    }

    /**
     * Verify that getProviderNames should return empty List in case calling
     * getSubjectAlternativeNames() throws the CertificateParsingException.
     */
    @Test
    public void testExceptionFromGetSubjectAlternativeNamesShouldReturnEmptyList()
            throws Exception {
        doThrow(new CertificateParsingException()).when(
                mX509Certificate).getSubjectAlternativeNames();

        assertTrue(mASN1SubjectAltNamesParser.getProviderNames(mX509Certificate).isEmpty());
    }

    /**
     * Verify that getProviderNames should return empty List in case the subjectAlternativeNames
     * doesn't comply with the otherName sequence
     */
    @Test
    public void testNonOtherNameFromGetSubjectAlternativeNamesShouldReturnEmptyList()
            throws Exception {
        mNewNames.add(makeAltNames(new GeneralName(GeneralName.dNSName, LOCAL_HOST_NAME), "DER"));
        mNewNames.add(
                makeAltNames(new GeneralName(GeneralName.iPAddress,
                        new DEROctetString(LOCAL_HOST_ADDRESS)), "DER"));
        when(mX509Certificate.getSubjectAlternativeNames()).thenReturn(
                Collections.unmodifiableCollection(mNewNames));

        assertTrue(mASN1SubjectAltNamesParser.getProviderNames(mX509Certificate).isEmpty());
    }

    /**
     * Verify that getProviderNames should return empty List in case the subjectAlternativeNames
     * returns the result that has only one element in the list.
     */
    @Test
    public void testInvalidFormatFromGetSubjectAlternativeNamesShouldReturnEmptyList()
            throws Exception {
        // Create a list that has one element as result for getSubjectAlternativeNames()
        // to violate the expected format that has two elements in a list.
        List<Object> nameEntry = new ArrayList<>(1);
        nameEntry.add(Integer.valueOf(4));
        mNewNames.add(nameEntry);
        when(mX509Certificate.getSubjectAlternativeNames()).thenReturn(
                Collections.unmodifiableCollection(mNewNames));

        assertTrue(mASN1SubjectAltNamesParser.getProviderNames(mX509Certificate).isEmpty());
    }

    /**
     * Verify that getProviderNames should return a List that has a result in case the
     * subjectAlternativeNames has valid friendly name for service provider.
     */
    @Test
    public void testValidEntryFromGetSubjectAlternativeNamesShouldReturnList()
            throws Exception {
        // Create the valid entry for FriendlyName
        ASN1EncodableVector v = new ASN1EncodableVector();
        v.add(WFA_OID_HOTSPOT_FRIENDLYNAME);
        v.add(new DERTaggedObject(12, new DERUTF8String(TEST_LANGUAGE + TEST_FRIENDLY_NAME)));
        mNewNames.add(
                makeAltNames(new GeneralName(GeneralName.otherName, new DERSequence(v)), "DER"));

        when(mX509Certificate.getSubjectAlternativeNames()).thenReturn(
                Collections.unmodifiableCollection(mNewNames));

        List<Pair<Locale, String>> result = mASN1SubjectAltNamesParser.getProviderNames(
                mX509Certificate);

        assertThat(result.size(), is(1));
        assertEquals(EXPECTED_RESULT, result.get(0));
    }

    /**
     * Helper function to create an entry complying with the format returned
     * {@link X509Certificate#getSubjectAlternativeNames()}
     */
    private List<Object> makeAltNames(GeneralName name, String encoding) throws Exception {
        List<Object> nameEntry = new ArrayList<>(2);
        nameEntry.add(Integer.valueOf(name.getTagNo()));
        nameEntry.add(name.getEncoded(encoding));

        return nameEntry;
    }
}
