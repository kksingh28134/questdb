use parquet2::encoding::Encoding;
use parquet2::page::Page;
use parquet2::schema::types::PrimitiveType;

use crate::parquet_write::file::WriteOptions;
use crate::parquet_write::ParquetResult;
use crate::parquet_write::util::{build_plain_page, encode_bool_iter};

fn encode_plain<const N: usize>(data: &[[u8; N]], buffer: &mut Vec<u8>) {
    // append the non-null values
    data.iter().for_each(|x| {
        //TODO: if not a null
        buffer.extend_from_slice(x);
    })
}

pub fn bytes_to_page<const N: usize>(
    data: &[[u8; N]],
    options: WriteOptions,
    type_: PrimitiveType,
) -> ParquetResult<Page> {
    let mut buffer = vec![];
    let mut null_count = 0;

    let nulls_iterator = data.iter().map(|bytes| {
        // TODO: null
        if false {
            null_count += 1;
            false
        } else {
            true
        }
    });

    let length = nulls_iterator.len();
    encode_bool_iter(&mut buffer, nulls_iterator, options.version)?;
    let definition_levels_byte_length = buffer.len();
    encode_plain(data, &mut buffer);
    build_plain_page(
        buffer,
        length,
        length,
        null_count,
        definition_levels_byte_length,
        None, // do we really want a binary statistics?
        type_,
        options,
        Encoding::Plain,
    )
        .map(Page::Data)
}