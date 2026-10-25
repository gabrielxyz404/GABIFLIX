package io.github.gabrielxyz404.gabiflix.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Símbolo simples de cada catálogo, desenhado no app (sem imagens). Formas genéricas inspiradas no serviço. */
class ServiceIcon extends View {

    private String id = "";
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path p = new Path();

    ServiceIcon(Context c) {
        super(c);
        fill.setStyle(Paint.Style.FILL);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    void set(String serviceId, int color) {
        id = serviceId;
        fill.setColor(color);
        line.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight()) / 100f;
        c.save();
        c.translate((getWidth() - 100 * s) / 2f, (getHeight() - 100 * s) / 2f);
        c.scale(s, s);
        line.setStrokeWidth(7);
        p.reset();
        p.setFillType(Path.FillType.EVEN_ODD);
        switch (id) {
            case "netflix": // N reto
                p.addRect(22, 10, 38, 90, Path.Direction.CW);
                p.addRect(62, 10, 78, 90, Path.Direction.CW);
                c.drawPath(p, fill);
                p.reset();
                p.moveTo(22, 10); p.lineTo(38, 10); p.lineTo(78, 90); p.lineTo(62, 90); p.close();
                c.drawPath(p, fill);
                break;
            case "prime": // seta reta
                p.addRect(10, 43, 62, 57, Path.Direction.CW);
                c.drawPath(p, fill);
                p.reset();
                p.moveTo(56, 24); p.lineTo(92, 50); p.lineTo(56, 76); p.close();
                c.drawPath(p, fill);
                break;
            case "disney": // castelo
                p.addRect(18, 55, 82, 90, Path.Direction.CW);          // muralha
                p.addRect(40, 34, 60, 90, Path.Direction.CW);          // torre do meio
                p.addRect(14, 42, 28, 90, Path.Direction.CW);          // torre esquerda
                p.addRect(72, 42, 86, 90, Path.Direction.CW);          // torre direita
                p.setFillType(Path.FillType.WINDING);
                c.drawPath(p, fill);
                p.reset();
                p.moveTo(38, 36); p.lineTo(50, 8); p.lineTo(62, 36); p.close();   // telhados
                p.moveTo(12, 44); p.lineTo(21, 24); p.lineTo(30, 44); p.close();
                p.moveTo(70, 44); p.lineTo(79, 24); p.lineTo(88, 44); p.close();
                c.drawPath(p, fill);
                Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
                hole.setColor(0xFF000000);
                hole.setAlpha(150);
                c.drawRoundRect(new RectF(44, 70, 56, 90), 6, 6, hole);     // porta
                c.drawRect(47, 46, 53, 54, hole);                           // janela
                break;
            case "hbo": // claquete
                c.drawRoundRect(new RectF(14, 42, 86, 88), 6, 6, fill);
                p.moveTo(14, 26); p.lineTo(84, 14); p.lineTo(87, 30); p.lineTo(17, 42); p.close();
                c.drawPath(p, fill);
                line.setStrokeWidth(5);
                line.setColor(0x99000000);
                c.drawLine(36, 22, 42, 37, line);
                c.drawLine(58, 18, 64, 33, line);
                line.setColor(fill.getColor());
                break;
            case "appletv": // televisão
                c.drawRoundRect(new RectF(10, 18, 90, 72), 8, 8, fill);
                Paint screen = new Paint(Paint.ANTI_ALIAS_FLAG);
                screen.setColor(0x99000000);
                c.drawRoundRect(new RectF(17, 25, 83, 65), 4, 4, screen);
                c.drawRoundRect(new RectF(34, 78, 66, 85), 3, 3, fill);
                c.drawRect(46, 72, 54, 80, fill);
                break;
            case "paramount": // montanha
                p.moveTo(6, 86); p.lineTo(42, 22); p.lineTo(58, 48); p.lineTo(68, 36); p.lineTo(94, 86); p.close();
                c.drawPath(p, fill);
                break;
            case "globoplay": // globo
                line.setStrokeWidth(6);
                c.drawCircle(50, 50, 38, line);
                c.drawOval(new RectF(32, 12, 68, 88), line);
                c.drawLine(12, 50, 88, 50, line);
                c.drawLine(50, 12, 50, 88, line);
                break;
            case "clarovideo": // play no círculo
                line.setStrokeWidth(7);
                c.drawCircle(50, 50, 38, line);
                p.moveTo(42, 32); p.lineTo(68, 50); p.lineTo(42, 68); p.close();
                c.drawPath(p, fill);
                break;
            default: // CineTime: rolo de filme
                p.addCircle(50, 50, 40, Path.Direction.CW);
                p.addCircle(50, 50, 7, Path.Direction.CW);
                for (int i = 0; i < 6; i++) {
                    double a = Math.toRadians(i * 60 - 90);
                    p.addCircle(50 + (float) (Math.cos(a) * 24), 50 + (float) (Math.sin(a) * 24), 9, Path.Direction.CW);
                }
                c.drawPath(p, fill);
                break;
        }
        c.restore();
    }
}
