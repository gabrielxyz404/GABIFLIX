package io.github.gabrielxyz404.gabiflix;
import java.util.*;
public class T2 { public static void main(String[] a){
  long total=110*60000L; long[] truth=T.base(total,1500); long[] ref=T.variant(truth,0.12,0.10,250); long[] pt=T.variant(truth,0.08,0.08,250); T.PT_TRUTH=T.LASTTRUTH;
  for(double s: new double[]{1.0005,1.001,1.002,1.0043,1.01}) T.report("escala real "+s+" (+3 s)",ref,T.xf(pt,1/s,-3000),truth);
  // PT é legenda de versao com 'intro' 25 s só nos primeiros 5 min? -> deslocamento só no começo
  long[] part=pt.clone(); for(int i=0;i<part.length;i++) if(part[i]<8*60000L) part[i]+=25000; T.report("+25 s só nos primeiros 8 min",ref,part,truth);
  // ref com 1 candidata ruim: ref curta (30% das falas)
  long[] sparse=T.variant(truth,0.7,0.0,250); T.PT_TRUTH=T.LASTTRUTH; T.report("referência esparsa (30% das falas)",sparse,T.xf(pt,1,-7000),truth);
}}
