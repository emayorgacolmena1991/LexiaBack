-- Textos guardados con tildes rotas (UTF-8 leído como Windows-1252): «DiagnÃ³stico» en vez de
-- «Diagnóstico». El origen eran literales mal codificados en CoactivaExpedienteAnalisisWorker
-- (ya corregidos); esto arregla lo que quedó guardado. Solo toca filas que contienen 'Ã'.
-- Las secuencias se escriben con chr() para que no dependan de la codificación de este archivo.

CREATE OR REPLACE FUNCTION app.tmp_corregir_tildes(t TEXT) RETURNS TEXT
LANGUAGE sql IMMUTABLE AS $$
  SELECT replace(replace(replace(replace(replace(replace(replace(
         replace(replace(replace(replace(replace(replace(t,
    chr(195) || chr(161),  chr(225)),   -- á
    chr(195) || chr(169),  chr(233)),   -- é
    chr(195) || chr(173),  chr(237)),   -- í
    chr(195) || chr(179),  chr(243)),   -- ó
    chr(195) || chr(186),  chr(250)),   -- ú
    chr(195) || chr(177),  chr(241)),   -- ñ
    chr(195) || chr(188),  chr(252)),   -- ü
    chr(195) || chr(129),  chr(193)),   -- Á
    chr(195) || chr(8240), chr(201)),   -- É
    chr(195) || chr(141),  chr(205)),   -- Í
    chr(195) || chr(8220), chr(211)),   -- Ó
    chr(195) || chr(353),  chr(218)),   -- Ú
    chr(195) || chr(8216), chr(209))    -- Ñ
$$;

UPDATE app.coactiva_evento
   SET titulo = app.tmp_corregir_tildes(titulo)
 WHERE position(chr(195) IN titulo) > 0;

UPDATE app.coactiva_evento
   SET detalle = app.tmp_corregir_tildes(detalle)
 WHERE detalle IS NOT NULL AND position(chr(195) IN detalle) > 0;

UPDATE app.coactiva_analisis
   SET error = app.tmp_corregir_tildes(error)
 WHERE error IS NOT NULL AND position(chr(195) IN error) > 0;

UPDATE app.coactiva_archivo
   SET motivo_rechazo_ia = app.tmp_corregir_tildes(motivo_rechazo_ia)
 WHERE motivo_rechazo_ia IS NOT NULL AND position(chr(195) IN motivo_rechazo_ia) > 0;

DROP FUNCTION app.tmp_corregir_tildes(TEXT);
