package io.github.gabrielxyz404.gabiflix;
import java.util.*;
public class T {
  static String piece(SubtitleAlign.Fit f){ return f.piecewise()?" PARTES("+f.cuts.length+" cortes)":""; }
  static long[] PT_TRUTH; static Random R = new Random(42);
  // legenda realista: cenas de diálogo (rajadas) separadas por silêncios, 110 min
  static long[] base(long totalMs, int n){
    ArrayList<Long> l=new ArrayList<>(); long t=90000;
    while(l.size()<n && t<totalMs-120000){
      int burst=5+R.nextInt(30);
      for(int i=0;i<burst&&l.size()<n;i++){ t+=1200+(long)(-Math.log(1-R.nextDouble())*3500); l.add(t);} 
      t+=20000+R.nextInt(240000);
    }
    long[] a=new long[l.size()]; for(int i=0;i<a.length;i++)a[i]=l.get(i); return a;
  }
  // "outro idioma": remove ~12% e adiciona ~10%, jitter +-250ms (cortes de fala diferentes)
  static long[] LASTTRUTH;
  static long[] variant(long[] r,double drop,double add,int jit){
    ArrayList<long[]> l=new ArrayList<>();
    for(long x:r){ if(R.nextDouble()<drop) continue; l.add(new long[]{x+(long)((R.nextDouble()*2-1)*jit),x}); if(R.nextDouble()<add) l.add(new long[]{x+800+R.nextInt(2000),x+800}); }
    l.sort((p,q)->Long.compare(p[0],q[0]));
    long[] a=new long[l.size()],t=new long[l.size()]; for(int i=0;i<a.length;i++){a[i]=l.get(i)[0];t[i]=l.get(i)[1];} LASTTRUTH=t; return a;
  }
  static long[] xf(long[] a,double k,long off){ long[] o=new long[a.length]; for(int i=0;i<a.length;i++)o[i]=Math.round(a[i]*k)+off; return o; }
  static long[] cutMid(long[] a,long at,long len){ // legenda de versão com 'len' ms a mais (intro/logos) no meio => depois de 'at' deslocado +len
    long[] o=a.clone(); for(int i=0;i<o.length;i++) if(o[i]>=at)o[i]+=len; return o; }
  // erro residual: aplica a transformação estimada e compara com a verdade (por fala)
  static void report(String name,long[] ref,long[] cand,long[] ignored){ long[] truthOfCand=PT_TRUTH;
    SubtitleAlign.Fit f=SubtitleAlign.align(ref,cand);
    int ok300=0,ok1000=0; long max=0; int n=Math.min(cand.length,truthOfCand.length);
    for(int i=0;i<n;i++){ long t=f.map(cand[i]); long e=Math.abs(t-truthOfCand[i]); if(e<=300)ok300++; if(e<=1000)ok1000++; max=Math.max(max,e);} 
    System.out.printf(Locale.ROOT,"%-46s score=%.2f aceita=%-5s escala=%.4f off=%6dms | falas<=0.3s: %5.1f%%  <=1s: %5.1f%%  erro máx=%.1fs%s%n",name,f.score,f.ok(),f.scale,f.offsetMs,100.0*ok300/n,100.0*ok1000/n,max/1000.0,piece(f));
  }
  public static void main(String[] x){
    long total=110*60000L; long[] truth=base(total,1500); // tempos corretos para ESTE vídeo
    System.out.println("falas base="+truth.length+" último="+truth[truth.length-1]/60000.0+" min");
    long[] ref=variant(truth,0.12,0.10,250);   // referência EN (do hash) correta
    long[] ptT0=null; long[] pt =variant(truth,0.08,0.08,250); PT_TRUTH=LASTTRUTH; long[] ptT=PT_TRUTH;
    report("0 controle: PT já correto",ref,pt,truth);
    for(long o: new long[]{3000,7000,-12000,45000,-90000,115000,125000,150000,-200000})
      report("offset "+o/1000+" s",ref,xf(pt,1,-o),truth);
    // PT feita para 25 fps, vídeo 23.976 (legenda mais curta): cand = truth*23.976/25
    report("fps: PT 25fps p/ vídeo 23.976",ref,xf(pt,23.976/25,0),truth);
    report("fps: PT 23.976 p/ vídeo 25",ref,xf(pt,25/23.976,0),truth);
    report("fps 25 + offset +7 s",ref,xf(pt,23.976/25,-7000),truth);
    report("fps 25 + offset -30 s",ref,xf(pt,23.976/25,30000),truth);
    report("escala 1.0010 (30/29.97)",ref,xf(pt,1/1.001,0),truth);
    report("escala 1.0043 sem ser 24/23.976 exato(1.0042)",ref,xf(pt,1/1.0043,2000),truth);
    report("escala 1.02 (fora da lista)",ref,xf(pt,1/1.02,0),truth);
    report("escala 1.04 (PAL 4%, lista tem 4.27%)",ref,xf(pt,1/1.04,0),truth);
    // corte: legenda de versão com 30 s a mais no meio (a partir de 55 min) -> primeira metade OK, segunda +30 s
    long[] ptCut=cutMid(pt,55*60000L,30000); report("versão +30 s no meio (global)",ref,ptCut,truth);
    // cortes (fase 2): 2 trechos e 3 trechos
    long[] cutHalf=cutMid(pt,55*60000L,30000); report("FASE2 corte +30 s aos 55 min (50/50)",ref,cutHalf,truth);
    report("FASE2 corte +12 s aos 20 min",ref,cutMid(pt,20*60000L,12000),truth);
    report("FASE2 corte -20 s aos 40 min",ref,cutMid(pt,40*60000L,-20000),truth);
    long[] three=cutMid(cutMid(pt,25*60000L,15000),70*60000L,-40000); report("FASE2 2 cortes (+15 s aos 25, -40 s aos 70)",ref,three,truth);
    report("FASE2 corte + offset global -7 s",ref,xf(cutMid(pt,50*60000L,25000),1,-7000),truth);
    report("FASE2 corte aos 90 min (pequeno trecho final)",ref,cutMid(pt,90*60000L,30000),truth);
    report("FASE2 escala livre 1.02 + corte aos 50 min",ref,xf(cutMid(pt,50*60000L,20000),1/1.02,0),truth);
    // intro extra de 40 s no começo (toda a legenda +40 s) 
    report("versão com intro +40 s (todo o arquivo)",ref,xf(pt,1,40000),truth);
    long[] ptCut2=cutMid(pt,20*60000L,12000); report("corte 12 s aos 20 min",ref,ptCut2,truth);
    // PT com muito menos falas (legenda simplificada) 
    long[] v1=variant(truth,0.45,0.02,250); PT_TRUTH=LASTTRUTH; report("PT com 45% das falas removidas",ref,xf(v1,1,-6000),truth); long[] v2=variant(truth,0.1,0.1,600); PT_TRUTH=LASTTRUTH; report("PT jitter 600 ms (outra tradução)",ref,xf(v2,1,-6000),truth); PT_TRUTH=ptT;
    // referência errada: de outra versão com offset +25 s no meio
    long[] refBad=cutMid(ref,55*60000L,30000);
    report("referência com corte (2a metade +30s) e PT sem",refBad,pt,truth);
    // controle negativo: legenda de outro filme
    long[] other=base(total,1500); SubtitleAlign.Fit f=SubtitleAlign.align(ref,other);
    System.out.printf(Locale.ROOT,"controle negativo (outro filme): score=%.2f aceita=%s%n",f.score,f.ok());
    long[] ep=base(45*60000L,500); long[] epRef=variant(ep,0.1,0.1,250); long[] epPt=variant(ep,0.1,0.1,250); PT_TRUTH=LASTTRUTH;
    for(long o: new long[]{7000,40000}) report("série 45 min offset "+o/1000+" s",epRef,xf(epPt,1,-o),ep);
    // falsa: referência de episódio errado (outro ep) 
    long[] ep2=base(45*60000L,500); f=SubtitleAlign.align(variant(ep2,0.1,0.1,250),xf(epPt,1,-7000));
    System.out.printf(Locale.ROOT,"controle negativo série: score=%.2f aceita=%s%n",f.score,f.ok());
  }
}
