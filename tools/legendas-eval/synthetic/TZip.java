package io.github.gabrielxyz404.gabiflix;
import java.io.*; import java.util.zip.*;
public class TZip { public static void main(String[] a) throws Exception {
 StringBuilder sb=new StringBuilder();
 for(int i=0;i<30;i++) sb.append(i+1).append("\n00:00:").append(String.format("%02d",i)).append(",000 --> 00:00:").append(String.format("%02d",i)).append(",500\nVocê não sabe o que isso é, nós também estou aqui\n\n");
 ByteArrayOutputStream bo=new ByteArrayOutputStream(); ZipOutputStream z=new ZipOutputStream(bo);
 z.putNextEntry(new ZipEntry("leiame.txt")); z.write("só texto de leitura, nada de legenda aqui dentro deste arquivo de texto".getBytes("UTF-8")); z.closeEntry();
 z.putNextEntry(new ZipEntry("filme.srt")); z.write(sb.toString().getBytes("UTF-8")); z.closeEntry(); z.close();
 String t=SubtitleText.decode(bo.toByteArray());
 System.out.println(t==null? "NULL (bug)" : "OK len="+t.length()+" começa="+t.substring(0,20).replace("\n","|"));
}}
