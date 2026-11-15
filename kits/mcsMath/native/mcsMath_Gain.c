#include "sedona.h"

Cell mcsMath_Gain_clamp(SedonaVM* vm, Cell* params) {
  float v = params[0].fval, lo = params[1].fval, hi = params[2].fval;
  Cell result;
  result.fval = v < lo ? lo : v > hi ? hi : v;
  return result;
}
