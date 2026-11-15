package ru.bccontrol.sedonastudio.protocol;

import ru.bccontrol.sedonastudio.model.Tr;
import sedona.Slot;
import sedona.Value;

/** Strict scalar parsing shared by prepare and unit checks. No coercion/wrapping. */
public final class ScalarValues {
  public static Value parse(Slot slot, String raw) {
    if (!slot.isProp()
        || slot.name.equals("meta")
        || slot.facets.getb("readonly", false)
        || sensitive(slot.name))
      throw new IllegalArgumentException("Property is read only or unsupported");
    if (slot.isAsStr()) {
      Value text = sedona.Str.make(validateText(raw, slot.facets.geti("max", 0)));
      slot.assertValue(text);
      return text;
    }
    String s = validateLiteral(slot.type.name, raw);
    Value v;
    v = Value.defaultForType(slot.type.id).decodeString(s);
    slot.assertValue(v);
    return v;
  }

  public static String validateText(String raw, int max) {
    if (raw == null) throw new IllegalArgumentException(Tr.of("value.noText"));
    for (int i = 0; i < raw.length(); i++)
      if (raw.charAt(i) == 0 || raw.charAt(i) > 127)
        throw new IllegalArgumentException(Tr.of("value.asciiOnly"));
    if (max > 0 && raw.length() >= max)
      throw new IllegalArgumentException(Tr.of("value.maxLength", (max - 1)));
    return raw;
  }

  public static String validateLiteral(String qname, String raw) {
    String s = raw.trim(), t = qname.substring(qname.lastIndexOf(':') + 1);
    switch (t) {
      case "bool":
        if (!s.equals("true") && !s.equals("false"))
          throw new IllegalArgumentException("Expected true or false");
        break;
      case "byte":
      case "short":
      case "int":
      case "long":
        if (!s.matches("-?[0-9]+")) throw new IllegalArgumentException("Expected integer");
        long n = Long.parseLong(s);
        long
            lo =
                t.equals("byte") || t.equals("short")
                    ? 0
                    : t.equals("int") ? Integer.MIN_VALUE : Long.MIN_VALUE,
            hi =
                t.equals("byte")
                    ? 255
                    : t.equals("short")
                        ? 65535
                        : t.equals("int") ? Integer.MAX_VALUE : Long.MAX_VALUE;
        if (n < lo || n > hi) throw new IllegalArgumentException("Integer out of range");
        break;
      case "float":
      case "double":
        double number = Double.parseDouble(s);
        if (!Double.isFinite(number) || t.equals("float") && !Float.isFinite((float) number))
          throw new IllegalArgumentException("Expected finite number in type range");
        break;
      default:
        throw new IllegalArgumentException("Only scalar numeric/Boolean properties are supported");
    }
    return s;
  }

  public static boolean sensitive(String name) {
    return ru.bccontrol.sedonastudio.model.SecretFields.matches(name);
  }
}
