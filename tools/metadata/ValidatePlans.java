import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class ValidatePlans {
    public static void main(String[] args) throws Exception {
        PhoneNumberUtil util = PhoneNumberUtil.getInstance();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = reader.readLine()) != null) {
            String[] parts = line.split("\t", -1);
            if (parts.length != 4) continue;
            String iso = parts[0];
            String lineType = parts[1];
            String cc = parts[2];
            String nsn = parts[3];
            boolean valid = false;
            String region = "";
            String type = "UNKNOWN";
            try {
                PhoneNumber number = new PhoneNumber();
                number.setCountryCode(Integer.parseInt(cc));
                number.setNationalNumber(Long.parseLong(nsn));
                int leadingZeros = 0;
                while (leadingZeros < nsn.length() && nsn.charAt(leadingZeros) == '0') {
                    leadingZeros++;
                }
                if (leadingZeros > 0) {
                    number.setItalianLeadingZero(true);
                    number.setNumberOfLeadingZeros(leadingZeros);
                }
                valid = util.isValidNumberForRegion(number, iso);
                region = util.getRegionCodeForNumber(number);
                PhoneNumberType parsed = util.getNumberType(number);
                type = parsed.name();
            } catch (Exception ignored) {
            }
            System.out.println(
                iso + "\t" + lineType + "\t" + cc + "\t" + nsn + "\t" +
                valid + "\t" + region + "\t" + type
            );
        }
    }
}
