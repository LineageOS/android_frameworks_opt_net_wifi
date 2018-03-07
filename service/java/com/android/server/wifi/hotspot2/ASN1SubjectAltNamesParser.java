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

import android.text.TextUtils;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.org.bouncycastle.asn1.ASN1Encodable;
import com.android.org.bouncycastle.asn1.ASN1InputStream;
import com.android.org.bouncycastle.asn1.ASN1ObjectIdentifier;
import com.android.org.bouncycastle.asn1.ASN1Sequence;
import com.android.org.bouncycastle.asn1.DERTaggedObject;
import com.android.org.bouncycastle.asn1.DERUTF8String;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Provides parsing of SubjectAltNames extensions from X509Certificate
 */
public class ASN1SubjectAltNamesParser {
    private static final int OTHER_NAME = 0;
    private static final int ENTRY_COUNT = 2;
    private static final int LANGUAGE_CODE_LENGTH = 3;

    /**
     * The Operator Friendly Name shall be an {@code otherName} sequence for the subjectAltName.
     * If multiple Operator Friendly name values are required, then multiple {@code otherName}
     * fields shall be present in the OSU certificate.
     * The type-id of the {@code otherName} shall be an {@code ID_WFA_OID_HOTSPOT_FRIENDLYNAME}.
     * {@code ID_WFA_OID_HOTSPOT_FRIENDLYNAME} OBJECT IDENTIFIER ::= { 1.3.6.1.4.1.40808.1.1.1}
     * The {@code ID_WFA_OID_HOTSPOT_FRIENDLYNAME} contains only one language code and
     * friendly name for an operator and shall be encoded as an ASN.1 type UTF8String.
     * Refer to 7.3.2 section in Hotspot 2.0 R2 Technical_Specification document in detail.
     */
    @VisibleForTesting
    public static final String ID_WFA_OID_HOTSPOT_FRIENDLYNAME = "1.3.6.1.4.1.40808.1.1.1";
    private static ASN1SubjectAltNamesParser sASN1SubjectAltNamesParser = null;

    private ASN1SubjectAltNamesParser() {}

    /**
     * Obtain an instance of the {@link ASN1SubjectAltNamesParser} as a singleton object.
     */
    public static ASN1SubjectAltNamesParser getInstance() {
        if (sASN1SubjectAltNamesParser == null) {
            sASN1SubjectAltNamesParser = new ASN1SubjectAltNamesParser();
        }
        return sASN1SubjectAltNamesParser;
    }

    /**
     * Extracts provider names from a certificate by parsing subjectAltName extensions field
     * as an otherName sequence, which contains
     * id-wfa-hotspot-friendlyName oid + UTF8String denoting the friendlyName in the format below
     * <languageCode><friendlyName>
     * Note: Multiple language code will appear as additional UTF8 strings.
     * Note: Multiple friendly names will appear as multiple otherName sequences.
     *
     * @param providerCert the X509Certificate to be parsed
     * @return List of Pair representing {@Locale} and friendly Name for Operator found in the
     * certificate.
     */
    public  List<Pair<Locale, String>> getProviderNames(X509Certificate providerCert) {
        List<Pair<Locale, String>> providerNames = new ArrayList<>();
        Pair<Locale, String> providerName;
        if (providerCert == null) {
            return providerNames;
        }
        try {
            /**
             *  The ASN.1 definition of the {@code SubjectAltName} extension is:
             *  SubjectAltName ::= GeneralNames
             *  GeneralNames :: = SEQUENCE SIZE (1..MAX) OF GeneralName
             *
             *  GeneralName ::= CHOICE {
             *      otherName                       [0]     OtherName,
             *      rfc822Name                      [1]     IA5String,
             *      dNSName                         [2]     IA5String,
             *      x400Address                     [3]     ORAddress,
             *      directoryName                   [4]     Name,
             *      ediPartyName                    [5]     EDIPartyName,
             *      uniformResourceIdentifier       [6]     IA5String,
             *      iPAddress                       [7]     OCTET STRING,
             *      registeredID                    [8]     OBJECT IDENTIFIER}
             *  If this certificate does not contain a SubjectAltName extension, null is returned.
             *  Otherwise, a Collection is returned with an entry representing each
             *  GeneralName included in the extension.
             */
            Collection<List<?>> col = providerCert.getSubjectAlternativeNames();
            if (col == null) {
                return providerNames;
            }
            for (List<?> entry : col) {
                // Each entry is a List whose first entry is an Integer(the name type, 0-8)
                // and whose second entry is a String or a byte array.
                if (entry == null || entry.size() != ENTRY_COUNT) {
                    continue;
                }

                // The UTF-8 encoded Friendly Name shall be an otherName sequence.
                if ((Integer) entry.get(0) != OTHER_NAME) {
                    continue;
                }

                if (!(entry.toArray()[1] instanceof byte[])) {
                    continue;
                }

                byte[] octets = (byte[]) entry.toArray()[1];
                ASN1Encodable obj = new ASN1InputStream(octets).readObject();

                if (!(obj instanceof DERTaggedObject)) {
                    continue;
                }

                DERTaggedObject taggedObject = (DERTaggedObject) obj;
                ASN1Encodable encodedObject = taggedObject.getObject();

                if (!(encodedObject instanceof ASN1Sequence)) {
                    continue;
                }

                ASN1Sequence innerSequence = (ASN1Sequence) (encodedObject);
                ASN1Encodable innerObject = innerSequence.getObjectAt(0);

                if (!(innerObject instanceof ASN1ObjectIdentifier)) {
                    continue;
                }

                ASN1ObjectIdentifier oid = ASN1ObjectIdentifier.getInstance(innerObject);
                if (!oid.getId().equals(ID_WFA_OID_HOTSPOT_FRIENDLYNAME)) {
                    continue;
                }

                for (int index = 1; index < innerSequence.size(); index++) {
                    innerObject = innerSequence.getObjectAt(index);
                    if (!(innerObject instanceof DERTaggedObject)) {
                        continue;
                    }

                    DERTaggedObject innerSequenceObj = (DERTaggedObject) innerObject;
                    ASN1Encodable innerSequenceEncodedObject = innerSequenceObj.getObject();

                    if (!(innerSequenceEncodedObject instanceof DERUTF8String)) {
                        continue;
                    }

                    DERUTF8String providerNameUtf8 = (DERUTF8String) innerSequenceEncodedObject;
                    providerName = getFriendlyName(providerNameUtf8.getString());
                    if (providerName != null) {
                        providerNames.add(providerName);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return providerNames;
    }

    /**
     *  Extract the language code and friendly Name from the alternativeName.
     */
    private Pair<Locale, String> getFriendlyName(String alternativeName) {

        // Check for the minimum required length.
        if (TextUtils.isEmpty(alternativeName) || alternativeName.length() < LANGUAGE_CODE_LENGTH) {
            return null;
        }

        // Read the language string.
        String language =  alternativeName.substring(0, LANGUAGE_CODE_LENGTH);
        Locale locale;
        try {
            // The language code is a two or three character language code defined in ISO-639.
            locale = new Locale.Builder().setLanguage(language).build();
        } catch (Exception e) {
            return null;
        }

        // Read the friendlyName
        String friendlyName = alternativeName.substring(LANGUAGE_CODE_LENGTH);
        return Pair.create(locale, friendlyName);
    }
}

