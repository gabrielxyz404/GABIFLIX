package io.github.gabrielxyz404.gabiflix.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Símbolos desenhados no app (sem imagens), inspirados em cada serviço mas sem copiar os logos:
 * N, castelo, seta curva, maçã (fruta), montanha, globo, farol de carro, claquete e rolo de filme.
 * Também os ícones do topo (pesquisar, atualizar, conta, Mais Tarde).
 */
class ServiceIcon extends View {

    private String id = "";
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path p = new Path();

    ServiceIcon(Context c) {
        super(c);
        fill.setStyle(Paint.Style.FILL);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        hole.setColor(0x99000000);
    }

    void set(String iconId, int color) {
        id = iconId;
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
        p.reset();
        line.setStrokeWidth(8);
        switch (id) {
            case "netflix": // N
                c.drawRect(24, 10, 40, 90, fill);
                c.drawRect(60, 10, 76, 90, fill);
                p.moveTo(24, 10); p.lineTo(40, 10); p.lineTo(76, 90); p.lineTo(60, 90); p.close();
                c.drawPath(p, fill);
                break;
            case "disney": // castelo
                c.drawRect(18, 56, 82, 90, fill);
                c.drawRect(40, 34, 60, 90, fill);
                c.drawRect(14, 44, 28, 90, fill);
                c.drawRect(72, 44, 86, 90, fill);
                p.moveTo(37, 36); p.lineTo(50, 8); p.lineTo(63, 36); p.close();
                p.moveTo(11, 46); p.lineTo(21, 24); p.lineTo(31, 46); p.close();
                p.moveTo(69, 46); p.lineTo(79, 24); p.lineTo(89, 46); p.close();
                c.drawPath(p, fill);
                c.drawRoundRect(new RectF(44, 70, 56, 90), 6, 6, hole);
                c.drawRect(47, 46, 53, 54, hole);
                break;
            case "prime": // seta curvada
                line.setStrokeWidth(9);
                c.drawArc(new RectF(10, 10, 90, 72), 25, 130, false, line);
                p.moveTo(84, 44); p.lineTo(94, 66); p.lineTo(70, 66); p.close();
                c.drawPath(p, fill);
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
            case "appletv": // maçã (fruta inteira, com folha e cabinho)
                c.drawCircle(37, 58, 26, fill);
                c.drawCircle(63, 58, 26, fill);
                c.drawOval(new RectF(26, 54, 74, 92), fill);
                line.setStrokeWidth(6);
                c.drawLine(50, 34, 54, 16, line);
                p.moveTo(56, 22); p.cubicTo(66, 8, 82, 10, 86, 16); p.cubicTo(78, 28, 64, 30, 56, 22); p.close();
                c.drawPath(p, fill);
                break;
            case "paramount": // montanha
                p.moveTo(6, 86); p.lineTo(42, 22); p.lineTo(58, 48); p.lineTo(68, 36); p.lineTo(94, 86); p.close();
                c.drawPath(p, fill);
                p.reset();
                p.moveTo(42, 22); p.lineTo(51, 38); p.lineTo(45, 34); p.lineTo(38, 40); p.lineTo(34, 36); p.close();
                Paint snow = new Paint(Paint.ANTI_ALIAS_FLAG);
                snow.setColor(0xE6FFFFFF);
                c.drawPath(p, snow);
                break;
            case "globoplay": // globo
                line.setStrokeWidth(6);
                c.drawCircle(50, 50, 38, line);
                c.drawOval(new RectF(32, 12, 68, 88), line);
                c.drawLine(12, 50, 88, 50, line);
                c.drawLine(50, 12, 50, 88, line);
                break;
            case "clarovideo": // farol de carro: lâmpada e feixes de luz
                p.moveTo(46, 20); p.cubicTo(14, 20, 14, 80, 46, 80); p.close();
                c.drawPath(p, fill);
                line.setStrokeWidth(7);
                c.drawLine(58, 28, 90, 22, line);
                c.drawLine(58, 43, 90, 40, line);
                c.drawLine(58, 58, 90, 58, line);
                c.drawLine(58, 73, 90, 76, line);
                break;
            // ---------- ícones do topo ----------
            case "search":
                line.setStrokeWidth(9);
                c.drawCircle(43, 43, 24, line);
                c.drawLine(61, 61, 84, 84, line);
                break;
            case "refresh":
                line.setStrokeWidth(9);
                c.drawArc(new RectF(20, 20, 80, 80), 300, 290, false, line);
                p.moveTo(64, 10); p.lineTo(86, 26); p.lineTo(62, 36); p.close();
                c.drawPath(p, fill);
                break;
            case "account":
                c.drawCircle(50, 34, 18, fill);
                p.addRoundRect(new RectF(18, 58, 82, 92), new float[]{30, 30, 30, 30, 6, 6, 6, 6}, Path.Direction.CW);
                c.drawPath(p, fill);
                break;
            case "later": // relógio (como o "Mais Tarde" do celular)
                line.setStrokeWidth(8);
                c.drawCircle(50, 50, 36, line);
                c.drawLine(50, 50, 50, 28, line);
                c.drawLine(50, 50, 66, 60, line);
                break;
            default: { // CineTime: rolo de filme
                p.setFillType(Path.FillType.EVEN_ODD);
                p.addCircle(50, 50, 40, Path.Direction.CW);
                p.addCircle(50, 50, 7, Path.Direction.CW);
                for (int i = 0; i < 6; i++) {
                    double a = Math.toRadians(i * 60 - 90);
                    p.addCircle(50 + (float) (Math.cos(a) * 24), 50 + (float) (Math.sin(a) * 24), 9, Path.Direction.CW);
                }
                c.drawPath(p, fill);
                break;
            }
        }
        c.restore();
    }
}
